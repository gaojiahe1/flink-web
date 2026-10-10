package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;

/** 使用本地 HTTP 服务验证 Kubernetes 请求和无缓存刷新行为。 */
@ExtendWith(OutputCaptureExtension.class)
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

  /** 缺少快照 CRD、权限拒绝或网络故障都不能使已取得的部署状态消失。 */
  @Test
  void snapshotFailuresDoNotFailDeploymentQuery() throws Exception {
    var mapper = new ObjectMapper();
    var client = mock(KubernetesClient.class);
    when(client.base()).thenReturn("/deployments");
    var deployments =
        mapper.readTree(
            "{\"items\":[{\"metadata\":{\"name\":\"example\"},\"spec\":{\"job\":{}},\"status\":{\"jobStatus\":{\"state\":\"RUNNING\",\"savepointInfo\":{\"lastSavepoint\":{\"location\":\"s3://state/old\"}}}}}]}");
    when(client.call("GET", "/deployments", null)).thenReturn(deployments);
    String path = "/apis/flink.apache.org/v1beta1/namespaces/test/flinkstatesnapshots";
    when(client.call("GET", path, null))
        .thenReturn(com.fasterxml.jackson.databind.node.MissingNode.getInstance())
        .thenThrow(
            new BusinessException(
                HttpStatus.BAD_GATEWAY, "KUBERNETES_FORBIDDEN", "权限不足 (HTTP 403)"))
        .thenThrow(
            new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE, "KUBERNETES_UNAVAILABLE", "网络不可用"));
    var collector =
        new FlinkStateCollector(
            client, mapper, new PlatformProperties("test", null, null), snapshot());
    for (String state : java.util.List.of("UNSUPPORTED", "UNAVAILABLE", "UNAVAILABLE")) {
      var result = collector.collect();
      assertThat(result.path("snapshotQuery").path("state").asText()).isEqualTo(state);
      assertThat(result.at("/items/0/jobState").asText()).isEqualTo("RUNNING");
      assertThat(result.at("/items/0/savepointInfo/lastSavepoint/location").asText())
          .isEqualTo("s3://state/old");
      assertThat(result.at("/items/0/yaml").asText()).contains("example").isNotBlank();
    }
    when(client.call("GET", "/deployments", null))
        .thenThrow(new BusinessException(HttpStatus.BAD_GATEWAY, "KUBERNETES_FORBIDDEN", "权限不足"));
    assertThatThrownBy(collector::collect).isInstanceOf(BusinessException.class);
  }

  /** 新版快照资源读取成功时独立关联到部署并保留完整查询结果。 */
  @Test
  void snapshotsAreAssociatedWhenAvailable() throws Exception {
    var mapper = new ObjectMapper();
    var client = mock(KubernetesClient.class);
    when(client.base()).thenReturn("/deployments");
    when(client.call("GET", "/deployments", null))
        .thenReturn(
            mapper.readTree(
                "{\"items\":[{\"metadata\":{\"name\":\"example\"},\"spec\":{\"job\":{}}}]}"));
    when(client.call(eq("GET"), contains("flinkstatesnapshots"), isNull()))
        .thenReturn(
            mapper.readTree(
                "{\"items\":[{\"metadata\":{\"name\":\"sp\"},\"spec\":{\"jobReference\":{\"kind\":\"FlinkDeployment\",\"name\":\"example\"}},\"status\":{\"state\":\"COMPLETED\",\"path\":\"s3://state/new\"}}]}"));
    var result =
        new FlinkStateCollector(
                client, mapper, new PlatformProperties("test", null, null), snapshot())
            .collect();
    assertThat(result.at("/snapshotQuery/state").asText()).isEqualTo("AVAILABLE");
    assertThat(result.at("/items/0/snapshots/0/path").asText()).isEqualTo("s3://state/new");
  }

  /** 授权失败记录资源和状态码，正文、令牌与查询参数不进入日志。 */
  @Test
  void forbiddenRequestsHaveActionableSafeLogs(CapturedOutput output) {
    server.createContext(
        "/denied",
        exchange -> {
          byte[] bytes = "secret=upstream-sensitive".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(403, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    assertThatThrownBy(() -> client().call("GET", "/denied?token=query-sensitive", null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> {
              assertThat(ex.code()).isEqualTo("KUBERNETES_FORBIDDEN");
              assertThat(ex.getMessage()).contains("403", "ServiceAccount");
            });
    assertThat(output.getOut())
        .contains(
            "method=GET",
            "resource=/denied",
            "status=403",
            "BusinessException",
            "KubernetesClient.call")
        .doesNotContain("upstream-sensitive", "query-sensitive", "test-token");
  }

  /** 网络连接失败在 WARN 中包含原始堆栈与原因链，业务异常也保留根因。 */
  @Test
  void transportFailureKeepsOriginalCauseAndFullStack(CapturedOutput output) {
    var client = client();
    server.stop(0);
    assertThatThrownBy(() -> client.call("GET", "/unreachable", null))
        .isInstanceOf(BusinessException.class)
        .hasCauseInstanceOf(java.net.ConnectException.class);
    assertThat(output.getOut())
        .contains(
            "Kubernetes transport failed",
            "java.net.ConnectException",
            "KubernetesClient.call",
            "Caused by:");
  }

  /** 内容超限时保留最新末尾，避免展示旧前缀；兼容 UTF-8 截断边界。 */
  @Test
  void boundedLogKeepsLatestTail() {
    server.createContext(
        "/api/v1/namespaces/test/pods/example/log",
        exchange -> {
          byte[] bytes =
              ("旧日志".repeat(100000) + "\nINFO newest-line\n").getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    String text = client().text("/api/v1/namespaces/test/pods/example/log?tailLines=10000", 256);
    assertThat(text).endsWith("INFO newest-line\n").doesNotContain("\ufffd");
    assertThat(text.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(256);
  }
}
