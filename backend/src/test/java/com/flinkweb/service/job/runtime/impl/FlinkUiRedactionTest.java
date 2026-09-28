package com.flinkweb.service.job.runtime.impl;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** 验证 Flink UI 代理对参数和 JobManager 配置的脱敏。 */
class FlinkUiRedactionTest {
  /** 验证用户参数值全部脱敏且作业配置响应结构保持兼容。 */
  @Test
  void masksAllUserParametersWithoutBreakingJobConfigStructure() throws Exception {
    var node =
        new ObjectMapper()
            .readTree(
                """
{"jid":"abc123","name":"orders","execution-config":{"job-parallelism":2,"user-config":{"name":"hidden-token","nested":{"credential":"hidden-password"}}}}
""");
    var result = FlinkUiServiceImpl.redactConfig(node, false);
    assertThat(result.path("jid").asText()).isEqualTo("abc123");
    assertThat(result.at("/execution-config/job-parallelism").asInt()).isEqualTo(2);
    assertThat(result.toString()).doesNotContain("hidden-token", "hidden-password");
  }

  /** 验证 JobManager 配置即使字段名不含敏感关键词也不暴露原值。 */
  @Test
  void masksJobManagerConfigValuesEvenWhenKeyIsNotObviouslySensitive() throws Exception {
    var node =
        new ObjectMapper()
            .readTree(
                "[{\"key\":\"custom.setting\",\"value\":\"credential-in-arbitrary-property\"}]");
    var result = FlinkUiServiceImpl.redactConfig(node, false);
    assertThat(result.get(0).path("key").asText()).isEqualTo("custom.setting");
    assertThat(result.toString()).doesNotContain("credential-in-arbitrary-property");
  }
}
