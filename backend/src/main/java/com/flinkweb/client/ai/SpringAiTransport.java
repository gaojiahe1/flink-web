package com.flinkweb.client.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.exception.common.BusinessException;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Spring AI传输边界，集中处理响应预算、编码和厂商HTTP兼容，不负责工具执行。 */
final class SpringAiTransport {
  private static final int MAX_BYTES = 1024 * 1024;
  private static final HttpClient HTTP = HttpClient.newBuilder()
      .connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
  private final ObjectMapper mapper;
  private final ObjectNode model;
  private final int seconds;
  private final AtomicBoolean streamed = new AtomicBoolean(true);

  /** 每个请求独立计数，底层连接池复用且禁止重定向。 */
  SpringAiTransport(ObjectMapper mapper, ObjectNode model, int seconds) {
    this.mapper = mapper;
    this.model = model;
    this.seconds = seconds;
  }

  /** 同步传输继续受完整响应字节和截止时间限制。 */
  RestClient.Builder rest() {
    var factory = new JdkClientHttpRequestFactory(HTTP);
    factory.setReadTimeout(Duration.ofSeconds(seconds));
    return RestClient.builder()
        .requestFactory(new BoundedModelRequestFactory(factory, seconds))
        .defaultStatusHandler(status -> !status.is2xxSuccessful(), (request, response) -> {
          throw failure(response.getStatusCode().value(),
              new String(response.getBody().readNBytes(65536), StandardCharsets.UTF_8));
        });
  }

  /** 框架负责SSE解析，适配器只收敛HTTP响应与非流式兼容。 */
  WebClient.Builder web() {
    return WebClient.builder().clientConnector(new JdkClientHttpConnector(HTTP))
        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_BYTES))
        .filter((request, next) -> next.exchange(request).flatMap(this::boundedResponse));
  }

  /** 返回实际上游是否采用流式格式，不能以请求stream字段代替。 */
  boolean streamed() {
    return streamed.get();
  }

  /** 状态错误先分类，不让残缺错误JSON掩盖HTTP鉴权或限流原因。 */
  private Mono<ClientResponse> boundedResponse(ClientResponse response) {
    var bytes = new AtomicLong();
    var utf8 = new Utf8Guard();
    Flux<DataBuffer> body = response.bodyToFlux(DataBuffer.class).<DataBuffer>handle((buffer, sink) -> {
      if (bytes.addAndGet(buffer.readableByteCount()) > MAX_BYTES) {
        DataBufferUtils.release(buffer);
        sink.error(new IllegalStateException("模型响应超过大小限制"));
        return;
      }
      try {
        utf8.accept(buffer);
        sink.next(buffer);
      } catch (CharacterCodingException error) {
        DataBufferUtils.release(buffer);
        sink.error(new IllegalStateException("模型响应UTF-8无效"));
      }
    }).doOnComplete(utf8::finish);
    if (!response.statusCode().is2xxSuccessful()) {
      return rebuild(response, body, false).bodyToMono(String.class).defaultIfEmpty("")
          .flatMap(text -> Mono.error(failure(response.statusCode().value(), text)));
    }
    return Mono.just(rebuild(response, normalize(body), true));
  }

  /** 前导空白可跨任意网络片段，直到看到首个有效字节才决定JSON或SSE。 */
  private Flux<DataBuffer> normalize(Flux<DataBuffer> body) {
    var prefix = new AtomicBoolean(true);
    Flux<DataBuffer> significant = body.<DataBuffer>handle((buffer, sink) -> {
      if (prefix.get()) {
        int position = buffer.readPosition();
        while (position < buffer.writePosition() && whitespace(buffer.getByte(position))) position++;
        if (position == buffer.writePosition()) {
          DataBufferUtils.release(buffer);
          return;
        }
        buffer.readPosition(position);
        prefix.set(false);
      }
      sink.next(buffer);
    });
    return significant.switchOnFirst((signal, remaining) -> {
      if (signal.hasValue() && signal.get().getByte(signal.get().readPosition()) == '{') {
        return DataBufferUtils.join(remaining, MAX_BYTES).map(this::jsonAsEvent);
      }
      return remaining;
    });
  }

  /** 只移除JSON合法的ASCII空白，不把任意二进制或损坏UTF-8当分隔符。 */
  private boolean whitespace(byte value) {
    return value == ' ' || value == '\t' || value == '\r' || value == '\n';
  }

  /** 某些厂商把完整JSON标为SSE，只转换外壳，模型协议仍由框架解析。 */
  private DataBuffer jsonAsEvent(DataBuffer buffer) {
    try {
      byte[] bytes = new byte[buffer.readableByteCount()];
      buffer.read(bytes);
      var json = (ObjectNode) mapper.readTree(bytes);
      for (var choice : json.path("choices")) {
        if (choice.has("message")) ((ObjectNode) choice).set("delta", choice.get("message"));
      }
      streamed.set(false);
      return DefaultDataBufferFactory.sharedInstance.wrap(
          ("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8));
    } catch (Exception error) {
      throw new IllegalStateException("模型JSON响应无效");
    } finally {
      DataBufferUtils.release(buffer);
    }
  }

  /** 重建响应而非重复消费原流，移除转换后失效的长度头。 */
  private ClientResponse rebuild(ClientResponse original, Flux<DataBuffer> body, boolean sse) {
    return ClientResponse.create(original.statusCode()).headers(headers -> {
      headers.addAll(original.headers().asHttpHeaders());
      if (sse) headers.setContentType(MediaType.TEXT_EVENT_STREAM);
      headers.remove(HttpHeaders.CONTENT_LENGTH);
    }).body(body).build();
  }

  /** 对上游错误只返回稳定分类，不回显正文、密钥或SQL。 */
  private BusinessException failure(int status, String body) {
    String code = "AI_MODEL_HTTP_ERROR", message = "模型服务请求失败（HTTP " + status + "）";
    switch (status) {
      case 401 -> { code = "AI_MODEL_AUTH_FAILED"; message = "模型服务鉴权失败，请检查API Key"; }
      case 403 -> {
        boolean verification = model.path("baseUrl").asText().contains("api-inference.modelscope.cn")
            && body.toLowerCase(Locale.ROOT).contains("email")
            && body.toLowerCase(Locale.ROOT).contains("verif");
        code = verification ? "AI_MODEL_ACCOUNT_VERIFICATION_REQUIRED" : "AI_MODEL_ACCESS_DENIED";
        message = verification ? "ModelScope账号尚未完成邮箱验证" : "模型服务拒绝访问，请检查模型权限";
      }
      case 404 -> code = "AI_MODEL_NOT_FOUND";
      case 429 -> code = "AI_MODEL_RATE_LIMITED";
      case 400 -> code = "AI_MODEL_INVALID_REQUEST";
      default -> { }
    }
    return new BusinessException(HttpStatus.BAD_GATEWAY, code, message);
  }

  /** 跨网络片段严格验证UTF-8，只保留未完成码点，不解析SSE。 */
  private static final class Utf8Guard {
    private final java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
    private byte[] pending = new byte[0];

    /** 损坏字符不能被替换后继续进入可执行工具参数。 */
    private void accept(DataBuffer buffer) throws CharacterCodingException {
      byte[] bytes = new byte[pending.length + buffer.readableByteCount()];
      System.arraycopy(pending, 0, bytes, 0, pending.length);
      for (int index = 0; index < buffer.readableByteCount(); index++) {
        bytes[pending.length + index] = buffer.getByte(buffer.readPosition() + index);
      }
      var input = ByteBuffer.wrap(bytes);
      var result = decoder.decode(input, CharBuffer.allocate(bytes.length), false);
      if (result.isError()) result.throwException();
      pending = new byte[input.remaining()];
      input.get(pending);
    }

    /** 流结束仍不允许半个UTF-8字符。 */
    private void finish() {
      if (pending.length != 0) throw new IllegalStateException("模型UTF-8响应未完整结束");
    }
  }
}
