package com.flinkweb;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.collector.job.FlinkStateCollector;
import com.flinkweb.config.platform.PlatformProperties;
import com.flinkweb.exception.common.BusinessException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;

/** 使用本地 HTTP 服务验证 Kubernetes 请求和无缓存刷新行为。 */
class KubernetesClientTest {
  HttpServer server;
  Path token;
  AtomicInteger calls = new AtomicInteger();

  /** 启动本地 Kubernetes 协议替身并准备测试配置。 */
  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
              .isEqualTo("Bearer test-token");
          String body =
              exchange.getRequestURI().getPath().endsWith("/missing")
                  ? "{}"
                  : "{\"items\":[{\"metadata\":{\"name\":\"example\",\"resourceVersion\":\"5\"},\"spec\":{\"job\":{\"state\":\"running\"}},\"status\":{\"jobStatus\":{\"state\":\"RUNNING\"}}}]}";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(
              exchange.getRequestURI().getPath().endsWith("/missing") ? 404 : 200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    token = Files.createTempFile("flink-token", ".txt");
    Files.writeString(token, "test-token");
  }

  /** 关闭 HTTP 替身并释放监听端口。 */
  @AfterEach
  void stop() throws Exception {
    server.stop(0);
    Files.deleteIfExists(token);
  }

  /** 创建指向本地协议替身的 Kubernetes 客户端。 */
  KubernetesClient client() {
    var props = new PlatformProperties("test", null, null);
    return new KubernetesClient(
        new ObjectMapper(),
        props,
        "http://127.0.0.1:" + server.getAddress().getPort(),
        token.toString(),
        "missing-ca");
  }

  /** 验证每次刷新都会重新访问 API，响应只包含所需观测字段。 */
  @Test
  void eachRefreshReadsApiWithoutCacheAndReturnsOnlyStatus() {
    var client = client();
    assertThat(
            new FlinkStateCollector(
                    client,
                    new ObjectMapper(),
                    new PlatformProperties("test", null, null),
                    snapshot())
                .collect()
                .toString())
        .contains("RUNNING")
        .contains("queriedAt")
        .doesNotContain("test-token");
    new FlinkStateCollector(
            client, new ObjectMapper(), new PlatformProperties("test", null, null), snapshot())
        .collect();
    assertThat(calls.get()).isEqualTo(4);
  }

  /** 提供真实 YAML 脱敏器与空配置仓库，避免测试触碰生产密钥。 */
  com.flinkweb.collector.job.DeploymentYamlSnapshot snapshot() {
    var records = org.mockito.Mockito.mock(com.flinkweb.repository.metadata.RecordRepository.class);
    org.mockito.Mockito.when(records.list("configs")).thenReturn(java.util.List.of());
    return new com.flinkweb.collector.job.DeploymentYamlSnapshot(
        records,
        org.mockito.Mockito.mock(com.flinkweb.security.permission.SecretCipher.class),
        new ObjectMapper());
  }

  /** 验证 GET 不存在与修改失败采用不同错误语义。 */
  @Test
  void missingGetIsDifferentFromFailedMutation() {
    var client = client();
    assertThat(client.call("GET", "/missing", null).isMissingNode()).isTrue();
    assertThatThrownBy(() -> client.call("POST", "/missing", new ObjectMapper().createObjectNode()))
        .isInstanceOf(BusinessException.class);
  }
}
