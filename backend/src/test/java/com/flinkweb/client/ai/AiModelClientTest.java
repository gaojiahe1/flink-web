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

  /** 真实Spring AI HTTP调用保持稳定错误码，不回显供应商正文。 */
  @Test
  void classifiesHttpErrorsWithoutReturningUpstreamContent()throws Exception {
    var status=new java.util.concurrent.atomic.AtomicInteger();var captured=new java.util.concurrent.atomic.AtomicReference<String>();
    var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/v1/chat/completions",exchange->{captured.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] body="{\"error\":{\"message\":\"Please verify email first; secret-sentinel sql-sentinel\"}}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status.get(),body.length);try(var out=exchange.getResponseBody()){out.write(body);}});server.start();
    try{
      var client=spy(new AiModelClient(mapper));doReturn(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/chat/completions")).when(client).endpoint(anyString(),anyBoolean());
      var model=mapper.createObjectNode().put("baseUrl","https://api-inference.modelscope.cn").put("model","Qwen/Qwen3.5-27B");
      int[] statuses={401,403,404,429,400,500};String[] codes={"AI_MODEL_AUTH_FAILED","AI_MODEL_ACCOUNT_VERIFICATION_REQUIRED","AI_MODEL_NOT_FOUND","AI_MODEL_RATE_LIMITED","AI_MODEL_INVALID_REQUEST","AI_MODEL_HTTP_ERROR"};
      for(int n=0;n<statuses.length;n++){status.set(statuses[n]);String code=codes[n];assertThatThrownBy(()->client.complete(model,"secret-sentinel","system","sql-sentinel")).isInstanceOfSatisfying(BusinessException.class,error->{assertThat(error.code()).isEqualTo(code);assertThat(error.getMessage()).doesNotContain("secret-sentinel","sql-sentinel");});}
      var request=mapper.readTree(captured.get());assertThat(request.path("enable_thinking").asBoolean(true)).isFalse();assertThat(request.path("stream").asBoolean()).isFalse();
    }finally{server.stop(0);}
  }
}
