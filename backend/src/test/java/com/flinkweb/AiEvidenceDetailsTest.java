package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.client.flink.AiFlinkEvidenceClient;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.service.ai.support.*;
import com.flinkweb.service.job.runtime.DiagnosticService;
import com.flinkweb.service.permission.AccessService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 验证只读证据的最新尾部预算和空指标语义，不把缺数解释成正常。 */
class AiEvidenceDetailsTest {
  final ObjectMapper mapper = new ObjectMapper();
  final AiFlinkEvidenceClient flink = mock(AiFlinkEvidenceClient.class);
  final JobDraft job = new JobDraft("job", "job", "", "1.20.5", "", 1, null, null);

  /** 使用真实证据编排，仅隔离外部Flink读取。 */
  ObjectNode collect() {
    when(flink.read("job", "overview")).thenReturn(mapper.createObjectNode());
    when(flink.read("job", "taskmanagers")).thenReturn(mapper.createObjectNode().set("taskmanagers", mapper.createArrayNode()));
    var details = new AiJobEvidenceDetails(mock(KubernetesClient.class), mock(DiagnosticService.class), flink, mock(AiEvidenceDocuments.class), mapper, mock(AccessService.class));
    var bundle = mapper.createObjectNode();
    details.collect(job, mapper.createObjectNode(), bundle, "flinkMetrics"); return bundle;
  }

  /** 没有注册任何指标时，空数组必须附带明确缺数说明。 */
  @Test void emptyRegisteredMetricsAreUnavailable() {
    when(flink.read("job", "jobmanager/metrics")).thenReturn(mapper.createArrayNode());
    var bundle = collect();
    assertThat(bundle.at("/flink/jobManager")).isEmpty();
    assertThat(bundle.path("unavailable").toString()).contains("列表为空", "不能视为正常或使用量为0");
    verify(flink, never()).read(eq("job"), startsWith("jobmanager/metrics?"));
  }

  /** 存在其他指标但没有匹配JVM名称时，与空列表分别报告。 */
  @Test void noJvmMatchIsExplained() {
    when(flink.read("job", "jobmanager/metrics")).thenReturn(mapper.createArrayNode().add(mapper.createObjectNode().put("id", "Other.metric")));
    assertThat(collect().path("unavailable").toString()).contains("没有匹配的JVM / GC指标");
  }

  /** REST读取失败继续输出不可用提示，不伪造零值。 */
  @Test void metricReadFailureIsExplained() {
    when(flink.read("job", "jobmanager/metrics")).thenThrow(new IllegalStateException("unavailable"));
    var bundle = collect();
    assertThat(bundle.path("unavailable").toString()).contains("读取失败", "不能视为正常");
    assertThat(bundle.at("/flink/jobManager").isMissingNode()).isTrue();
  }

  /** 指标发现成功但值暂时为空，仍需标注缺数；真实0值可以正常保留。 */
  @Test void emptyValuesAndRealZeroAreDifferent() {
    var names = mapper.createArrayNode().add(mapper.createObjectNode().put("id", "Status.JVM.Memory.Heap.Used"));
    when(flink.read("job", "jobmanager/metrics")).thenReturn(names);
    when(flink.read("job", "jobmanager/metrics?get=Status.JVM.Memory.Heap.Used")).thenReturn(mapper.createArrayNode());
    assertThat(collect().path("unavailable").toString()).contains("指标值响应为空");
    when(flink.read("job", "jobmanager/metrics?get=Status.JVM.Memory.Heap.Used")).thenReturn(mapper.createArrayNode().add(mapper.createObjectNode().put("id", "Status.JVM.Memory.Heap.Used").put("value", "0")));
    var normal = collect();
    assertThat(normal.at("/flink/jobManager/0/value").asText()).isEqualTo("0");
    assertThat(normal.path("unavailable")).isEmpty();
  }

  /** 密集早期异常不能挤掉最后20行中的最新进展。 */
  @Test void denseErrorsReserveLatestTail() {
    var input = new StringBuilder();
    for (int i = 0; i < 400; i++) input.append("ERROR exception ").append(i).append(" x".repeat(80)).append('\n');
    input.append("INFO LATEST_PROGRESS_RECOVERING");
    String text = AiJobEvidenceDetails.excerpt(input.toString());
    assertThat(text.length()).isLessThanOrEqualTo(10000);
    assertThat(text).contains("1: ERROR", "LATEST_PROGRESS_RECOVERING", "最新尾部摘录");
  }

  /** 超长单行保留开头和结尾，行号及省略标记同样计入预算。 */
  @Test void oversizedLinesStillPreserveNewestSuffix() {
    String text = AiJobEvidenceDetails.excerpt("ERROR " + "中😀".repeat(10000) + " LATEST_LINE_END");
    assertThat(text.length()).isLessThanOrEqualTo(10000);
    assertThat(text).startsWith("1: ERROR").contains("单行省略").endsWith("LATEST_LINE_END\n");
    assertThat(new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)).isEqualTo(text);
    assertThat(AiJobEvidenceDetails.excerpt("")).isEmpty();
  }

  /** 一万字符边界包含前缀，重复和乱序行不会被制造出来。 */
  @Test void numberedOutputNeverExceedsBudget() {
    String input = "WARN ".repeat(3000) + "\n" + "INFO ok\n".repeat(50) + "TAIL_MARKER";
    String text = AiJobEvidenceDetails.excerpt(input);
    assertThat(text.length()).isLessThanOrEqualTo(10000);
    assertThat(text).contains("1: WARN", "52: TAIL_MARKER");
  }
}
