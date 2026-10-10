package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.service.system.impl.OperatorServiceImpl;
import org.junit.jupiter.api.Test;

/** 验证 Operator 支持范围、未知标签及按 Deployment 选择器查询 Pod 的行为。 */
class OperatorStatusTest {
  /** 核对每代版本边界，已弃用版本仍列入存量兼容但不推荐创建。 */
  @Test
  void compatibilityBoundaries() throws Exception {
    var service =
        new OperatorServiceImpl(
            mock(KubernetesClient.class), new ObjectMapper(), "ops", "operator", "operator");
    String[] maxima = {"1.19", "1.19", "1.19", "1.20", "2.0", "2.0", "2.2", "2.2", "2.4", "2.4"};
    String[] minima = {
      "1.15", "1.15", "1.15", "1.16", "1.17", "1.17", "1.19", "1.19", "1.19", "1.19"
    };
    for (int i = 7; i <= 16; i++) {
      var result = service.compatibility("registry:5000/team/operator:1." + i + ".0");
      assertThat(result.path("known").asBoolean()).isTrue();
      var versions = result.path("supported");
      assertThat(versions.get(0).asText()).isEqualTo(minima[i - 7]);
      assertThat(versions.get(versions.size() - 1).asText()).isEqualTo(maxima[i - 7]);
      assertThat(result.path("accepted").get(0).asText()).isEqualTo("1.15");
    }
    for (String tag :
        java.util.List.of(
            "latest",
            "1.6.0",
            "1.17.0",
            "custom-1.9.0",
            "1.9.0evil",
            "1.9.01.2",
            "1.14.0@sha256:abc"))
      assertThat(service.compatibility("repo:" + tag).path("known").asBoolean()).isFalse();
  }

  /** 带构建后缀的发行标签按开头版本识别，不从仓库名称或任意标签中猜测。 */
  @Test
  void recognizesReleasePrefixWithBuildSuffix() throws Exception {
    var service =
        new OperatorServiceImpl(
            mock(KubernetesClient.class), new ObjectMapper(), "ops", "operator", "operator");
    for (String tag :
        java.util.List.of(
            "1.9.0-xxxxx", "1.9.1-20241009", "v1.9.1-java17-build.2", "1.14.0-custom")) {
      var result = service.compatibility("registry:5000/team/operator:" + tag);
      assertThat(result.path("known").asBoolean()).isTrue();
      assertThat(result.path("version").asText())
          .isEqualTo(tag.replaceFirst("^v", "").split("-", 2)[0]);
    }
    assertThat(
            service
                .compatibility("repo:1.9.1-build@sha256:" + "a".repeat(64))
                .path("version")
                .asText())
        .isEqualTo("1.9.1");
  }

  /** Deployment 的镜像而非 Pod 的旧镜像决定版本，Pod 只提供实际运行状态。 */
  @Test
  void readsDeploymentAndPodsOnEveryRequest() throws Exception {
    var mapper = new ObjectMapper();
    var client = mock(KubernetesClient.class);
    when(client.call(eq("GET"), contains("/deployments/operator"), isNull()))
        .thenReturn(
            mapper.readTree(
                """
{"metadata":{"generation":2},"spec":{"replicas":1,"selector":{"matchLabels":{"app":"operator"}},"template":{"spec":{"containers":[{"name":"operator","image":"apache/operator:1.11.0"}]}}},"status":{"readyReplicas":1}}
"""));
    when(client.call(eq("GET"), contains("/pods?labelSelector="), isNull()))
        .thenReturn(
            mapper.readTree(
                """
{"items":[{"metadata":{"name":"operator-abc"},"status":{"phase":"Running","conditions":[{"type":"Ready","status":"True"}],"containerStatuses":[{"name":"operator","ready":true,"restartCount":2,"state":{"running":{}}}]}}]}
"""));
    var service = new OperatorServiceImpl(client, mapper, "ops", "operator", "operator");
    var result = service.inspect();
    assertThat(result.path("compatibility").path("version").asText()).isEqualTo("1.11.0");
    assertThat(result.path("pods").get(0).path("ready").asBoolean()).isTrue();
    service.inspect();
    verify(client, times(2)).call("GET", "/apis/apps/v1/namespaces/ops/deployments/operator", null);
    verify(client, times(2))
        .call("GET", "/api/v1/namespaces/ops/pods?labelSelector=app%3Doperator", null);
  }
}
