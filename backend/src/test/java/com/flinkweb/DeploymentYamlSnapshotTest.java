package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.collector.job.DeploymentYamlSnapshot;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.security.permission.SecretCipher;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 验证资源 YAML 查询保留版本与状态且不会泄露已知或内联敏感配置。 */
class DeploymentYamlSnapshotTest {
  /** 不修改原始 JSON，屏蔽嵌入 YAML、环境变量及参数中的密钥。 */
  @Test
  void redactsWithoutMutatingSource() throws Exception {
    var mapper = new ObjectMapper();
    var source =
        mapper.readTree(
            """
{"metadata":{"name":"demo","resourceVersion":"5","annotations":{"kubectl.kubernetes.io/last-applied-configuration":"private-json"}},"spec":{"flinkConfiguration":{"s3.secret-key":"secret123","custom":"known-secret"},"job":{"args":["private-argument"]},"podTemplate":{"spec":{"containers":[{"env":[{"name":"CUSTOM","value":"private-env"}]}]}}},"status":{"jobStatus":{"state":"RUNNING"},"reconciliationStatus":{"lastReconciledSpec":"private-embedded"}}}
""");
    var snapshot =
        new DeploymentYamlSnapshot(mock(RecordRepository.class), mock(SecretCipher.class), mapper);
    String yaml = snapshot.render(source, List.of("known-secret"));
    assertThat(yaml)
        .contains("RUNNING", "resourceVersion", "demo")
        .doesNotContain("private-", "secret123", "known-secret");
    assertThat(source.toString()).contains("private-env");
  }
}
