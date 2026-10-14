package com.flinkweb.client.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.stereotype.Component;

/** 调用管理员登记的 Chat Completions 服务；限制目标、重定向、传输量和完整请求时长。 */
@Component
public class AiModelClient {
  private final ObjectMapper mapper;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
      .followRedirects(HttpClient.Redirect.NEVER).build();

  /** 注入 JSON 编解码器，不持有业务记录或用户身份。 */
  public AiModelClient(ObjectMapper mapper) { this.mapper = mapper; }

  /** 校验 Base URL；内网模型必须显式允许，链路本地和元数据地址始终拒绝。 */
  public URI endpoint(String baseUrl, boolean privateNetwork) {
    try {
      URI base = URI.create(baseUrl);
      if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null
          || base.getFragment() != null || base.getPort() == 0 || base.getPort() > 65535
          || !("https".equals(base.getScheme()) || privateNetwork && "http".equals(base.getScheme()))
          || base.getRawPath().contains("%") || base.getPath().contains(".."))
        throw new IllegalArgumentException();
      for (InetAddress address : InetAddress.getAllByName(base.getHost())) {
        byte[] bytes = address.getAddress();
        boolean uniqueLocal = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()
            || !privateNetwork && (address.isLoopbackAddress() || address.isSiteLocalAddress() || uniqueLocal))
          throw new IllegalArgumentException();
      }
      return URI.create(base.toString().replaceAll("/+$", "") + "/chat/completions");
    } catch (Exception error) { throw new IllegalArgumentException("模型地址无效或目标网络未获允许"); }
  }

  /** 发送非流式请求；完整正文受预算约束，取消和超时关闭响应订阅。 */
  public ObjectNode complete(ObjectNode model, String apiKey, String system, String context) throws Exception {
    URI target = endpoint(model.path("baseUrl").asText(), model.path("privateNetwork").asBoolean());
    ObjectNode body = mapper.createObjectNode().put("model", model.path("model").asText())
        .put("stream", false).put("max_tokens", model.path("maxTokens").asInt(4096));
    body.putArray("messages").addObject().put("role", "system").put("content", system);
    body.withArray("messages").addObject().put("role", "user").put("content", context);
    int seconds = model.path("timeoutSeconds").asInt(90);
    var builder = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(seconds))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    if (!apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
    var limited = new LimitedBody();
    var pending = http.sendAsync(builder.build(), info -> limited);
    try {
      var response = pending.get(seconds, TimeUnit.SECONDS);
      if (response.statusCode() < 200 || response.statusCode() >= 300)
        throw new IllegalStateException("模型服务返回 HTTP " + response.statusCode() + "，请检查模型配置、额度和协议");
      var parsed = mapper.readTree(response.body());
      String text = parsed.at("/choices/0/message/content").asText("").trim();
      if (text.startsWith("```")) text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
      var result = mapper.readTree(text);
      if (!(result instanceof ObjectNode node)) throw new IllegalStateException("模型未返回约定的 JSON 对象");
      return node;
    } finally { limited.cancel(); pending.cancel(true); }
  }

  /** 有限正文订阅器，不允许上游以大响应耗尽平台内存。 */
  private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
    private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
    private Flow.Subscription subscription;
    private long count;

    /** 返回完整正文 Future。 */
    public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
    /** 保存订阅以便超时主动释放。 */
    public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
    /** 超过一 MiB 时取消，不保存超限数据。 */
    public void onNext(List<ByteBuffer> buffers) {
      for (var buffer : buffers) count += buffer.remaining();
      if (count > 1024 * 1024) { cancel(); delegate.onError(new IllegalStateException("模型响应超过大小限制")); }
      else delegate.onNext(buffers);
    }
    /** 转交通信故障，不记录正文。 */
    public void onError(Throwable error) { delegate.onError(error); }
    /** 通知响应读取完成。 */
    public void onComplete() { delegate.onComplete(); }
    /** 取消上游正文传输。 */
    void cancel() { if (subscription != null) subscription.cancel(); }
  }
}
