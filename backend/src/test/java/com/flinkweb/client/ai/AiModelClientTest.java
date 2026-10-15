package com.flinkweb.client.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.exception.common.BusinessException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 覆盖 ModelScope 非流式参数、根地址规范化和不泄漏上游正文的失败提示。 */
class AiModelClientTest {
  private final ObjectMapper mapper = new ObjectMapper();

  /** 根地址补 v1，已有版本前缀不重复；其余服务保留原前缀。 */
  @Test
  void normalizesOnlyModelScopeRoot() throws Exception {
    var address = InetAddress.getByAddress(new byte[] {8, 8, 8, 8});
    try (var dns = mockStatic(InetAddress.class)) {
      dns.when(() -> InetAddress.getAllByName(anyString())).thenReturn(new InetAddress[] {address});
      var client = new AiModelClient(mapper);
      for (String base :
          new String[] {
            "https://api-inference.modelscope.cn",
            "https://api-inference.modelscope.cn/",
            "https://api-inference.modelscope.cn/v1/"
          })
        assertThat(client.endpoint(base, false).toString())
            .isEqualTo("https://api-inference.modelscope.cn/v1/chat/completions");
      assertThat(client.endpoint("https://model.test/custom", false).getPath())
          .isEqualTo("/custom/chat/completions");
    }
  }

  /** 账号验证及常见 HTTP 错误使用稳定错误码，不转发上游回显的密钥或上下文。 */
  @Test
  @SuppressWarnings("unchecked")
  void classifiesHttpErrorsWithoutReturningUpstreamContent() throws Exception {
    var client = spy(new AiModelClient(mapper));
    doReturn(URI.create("https://api-inference.modelscope.cn/v1/chat/completions"))
        .when(client)
        .endpoint(anyString(), anyBoolean());
    var http = mock(HttpClient.class);
    ReflectionTestUtils.setField(client, "http", http);
    var response = (HttpResponse<byte[]>) mock(HttpResponse.class);
    when(response.body())
        .thenReturn(
            "{\"error\":{\"message\":\"Please verify email first; secret-sentinel sql-sentinel\"}}"
                .getBytes(StandardCharsets.UTF_8));
    when(http.sendAsync(any(), any(HttpResponse.BodyHandler.class)))
        .thenReturn(CompletableFuture.completedFuture(response));
    var model =
        mapper
            .createObjectNode()
            .put("baseUrl", "https://api-inference.modelscope.cn")
            .put("model", "Qwen/Qwen3.5-27B");
    int[] statuses = {401, 403, 404, 429, 400, 500};
    String[] codes = {
      "AI_MODEL_AUTH_FAILED",
      "AI_MODEL_ACCOUNT_VERIFICATION_REQUIRED",
      "AI_MODEL_NOT_FOUND",
      "AI_MODEL_RATE_LIMITED",
      "AI_MODEL_INVALID_REQUEST",
      "AI_MODEL_HTTP_ERROR"
    };
    for (int i = 0; i < statuses.length; i++) {
      when(response.statusCode()).thenReturn(statuses[i]);
      String code = codes[i];
      assertThatThrownBy(() -> client.complete(model, "secret-sentinel", "system", "sql-sentinel"))
          .isInstanceOfSatisfying(
              BusinessException.class,
              error -> {
                assertThat(error.code()).isEqualTo(code);
                assertThat(error.getMessage()).doesNotContain("secret-sentinel", "sql-sentinel");
              });
    }
    var request = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
    verify(http, times(statuses.length))
        .sendAsync(request.capture(), any(HttpResponse.BodyHandler.class));
    var bytes = new java.io.ByteArrayOutputStream();
    request
        .getValue()
        .bodyPublisher()
        .orElseThrow()
        .subscribe(
            new Flow.Subscriber<ByteBuffer>() {
              /** 请求完整请求体以验证序列化参数。 */
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              /** 合并发送的数据片段。 */
              public void onNext(ByteBuffer buffer) {
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
              }

              /** 传输错误直接使本测试失败。 */
              public void onError(Throwable error) {
                throw new AssertionError(error);
              }

              /** 正文收集完成。 */
              public void onComplete() {}
            });
    var body = mapper.readTree(bytes.toByteArray());
    assertThat(body.path("stream").asBoolean()).isFalse();
    assertThat(body.path("enable_thinking").asBoolean(true)).isFalse();
    assertThat(body.path("messages").size()).isEqualTo(2);
  }
}
