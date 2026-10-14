package com.flinkweb.client.flink;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.config.platform.PlatformProperties;
import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** 固定验证文件路由、传输限制及缺失轮转副本的兼容性。 */
class SessionRestClientTest {
  /** 响应头已到但正文停滞时仍按预算返回，上游正文订阅被取消。 */
  @Test
  @SuppressWarnings("unchecked")
  void budgetIncludesSlowStreamingBody() throws Exception {
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var release = new java.util.concurrent.CountDownLatch(1);
    var executor = java.util.concurrent.Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.createContext(
        "/",
        exchange -> {
          try {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("first chunk".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            release.await(3, java.util.concurrent.TimeUnit.SECONDS);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      var transport = HttpClient.newHttpClient();
      var client = mock(HttpClient.class);
      when(client.sendAsync(any(), any(HttpResponse.BodyHandler.class)))
          .thenAnswer(
              call -> {
                java.net.http.HttpRequest original = call.getArgument(0);
                HttpResponse.BodyHandler<byte[]> handler = call.getArgument(1);
                return transport.sendAsync(
                    java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create(
                                "http://127.0.0.1:" + server.getAddress().getPort() + "/"))
                        .timeout(original.timeout().orElseThrow())
                        .GET()
                        .build(),
                    handler);
              });
      var rest =
          new SessionRestClient(new ObjectMapper(), new PlatformProperties("flink-ns", null, null));
      ReflectionTestUtils.setField(rest, "client", client);
      long started = System.nanoTime();
      assertThatThrownBy(
              () ->
                  rest.budgetedValidationLog(
                      "fw-session-sql-validation",
                      "172.18.0.6:33999-b9a9fd",
                      "sql-validation.log",
                      java.time.Duration.ofMillis(250)))
          .hasMessageContaining("预算");
      assertThat(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
          .isLessThan(1500);
    } finally {
      release.countDown();
      server.stop(0);
      executor.shutdownNow();
    }
  }

  /** 超限 JSON 正文及时取消，位点和结果不会收到被截断的成功响应。 */
  @Test
  void boundedSubscriberRejectsOversizeAndCancelsSubscription() {
    var body = new BoundedResponseBody(4, 6);
    var subscription = mock(java.util.concurrent.Flow.Subscription.class);
    body.onSubscribe(subscription);
    body.onNext(java.util.List.of(java.nio.ByteBuffer.wrap(new byte[7])));
    assertThat(body.getBody().toCompletableFuture()).isCompletedExceptionally();
    verify(subscription).cancel();
  }

  /** 校验只允许两个固定文件，普通日志与路径穿越不能通过。 */
  @Test
  @SuppressWarnings("unchecked")
  void validationFileUsesNamedLogRouteAndAllowsAbsentBackup() throws Exception {
    var client = mock(HttpClient.class);
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body())
        .thenReturn(new ByteArrayInputStream("验证数据\n".getBytes(StandardCharsets.UTF_8)));
    when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    var rest =
        new SessionRestClient(new ObjectMapper(), new PlatformProperties("flink-ns", null, null));
    ReflectionTestUtils.setField(rest, "client", client);
    assertThat(
            rest.validationLog(
                "fw-session-sql-validation", "172.18.0.6:33999-b9a9fd", "sql-validation.log"))
        .isEqualTo("验证数据\n");
    var request = ArgumentCaptor.forClass(HttpRequest.class);
    verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
    assertThat(request.getValue().uri().toString())
        .isEqualTo(
            "http://fw-session-sql-validation-rest.flink-ns.svc:8081/taskmanagers/172.18.0.6:33999-b9a9fd/logs/sql-validation.log");
    assertThatThrownBy(() -> rest.validationLog("fw-session-sql-validation", "tm-1", "../config"))
        .hasMessageContaining("标识无效");
    assertThatThrownBy(
            () ->
                rest.budgetedValidationLog(
                    "fw-session-sql-validation",
                    "tm?redirect=x",
                    "sql-validation.log",
                    java.time.Duration.ofSeconds(1)))
        .hasMessageContaining("标识无效");
    when(response.statusCode()).thenReturn(404);
    when(response.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
    assertThat(rest.validationLog("fw-session-sql-validation", "tm-1", "sql-validation.log.1"))
        .isEmpty();
  }

  /** 尾部缓存保持固定大小，超限响应明确拒绝，不吞掉通信故障。 */
  @Test
  void tailIsBoundedAndOversizedStreamRejected() throws Exception {
    assertThat(
            SessionRestClient.readTail(
                new ByteArrayInputStream("abcdef".getBytes(StandardCharsets.UTF_8)), 4, 10))
        .isEqualTo("cdef");
    assertThatThrownBy(
            () -> SessionRestClient.readTail(new ByteArrayInputStream(new byte[11]), 4, 10))
        .hasMessageContaining("128 MiB");
  }
}
