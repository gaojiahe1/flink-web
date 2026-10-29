package com.flinkweb.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.repository.job.JobRepository;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.audit.impl.AuditServiceImpl;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 验证历史时间回退、目标快照与审计正文边界。 */
class AuditServiceTest {
  final ObjectMapper mapper = new ObjectMapper();
  final RecordRepository records = mock(RecordRepository.class);
  final JobRepository jobs = mock(JobRepository.class);
  final AuditServiceImpl audit = new AuditServiceImpl(records, mapper, jobs);

  /** 旧日志记录可恢复创建时间和作业名，不覆盖数据库原文。 */
  @Test
  void oldLogEventsUseCreationTimeAndResolveJobNamesWithoutChangingStoredRows() {
    var row =
        mapper
            .createObjectNode()
            .put("action", "JOB_LOG_READ")
            .put("target", "job-id")
            .put("createdAt", "2026-10-29T01:02:03Z");
    when(records.list("audit")).thenReturn(List.of(row));
    when(jobs.findById("job-id"))
        .thenReturn(
            Optional.of(
                new JobDraft(
                    "job-id", "order-sql", "", "1.20.5", "", 1, null, null, 7, "flink-0000007")));
    var result = audit.list().get(0);
    assertThat(result.path("time").asText()).isEqualTo("2026-10-29T01:02:03Z");
    assertThat(result.path("timeSource").asText()).isEqualTo("createdAt");
    assertThat(result.path("targetName").asText()).isEqualTo("order-sql");
    assertThat(result.path("targetReference").asText()).isEqualTo("flink-0000007");
    assertThat(row.has("time")).isFalse();
  }

  /** 缺失信息明确显示，新记录名称快照不受目标删除影响。 */
  @Test
  void missingTimeAndDeletedTargetAreExplicitAndNewSnapshotsSurviveDeletion() {
    String id = UUID.randomUUID().toString();
    var missing =
        mapper
            .createObjectNode()
            .put("action", "JOB_START")
            .put("target", id)
            .put("time", "broken");
    var snapshot =
        mapper
            .createObjectNode()
            .put("action", "DRAFT_DELETE")
            .put("target", id)
            .put("targetName", "deleted-orders")
            .put("time", "2026-10-29T01:00:00Z");
    when(records.list("audit")).thenReturn(List.of(missing, snapshot));
    var result = audit.list();
    assertThat(result.get(0).path("time").asText()).isEmpty();
    assertThat(result.get(0).path("targetName").asText()).isEqualTo("已删除或未登记的作业");
    assertThat(result.get(1).path("targetName").asText()).isEqualTo("deleted-orders");
  }

  /** 诊断只保存定位字段，不持久化任意传入的敏感正文。 */
  @Test
  void recordsTimestampSnapshotAndOnlyWhitelistedDiagnosticContext() {
    when(records.save(eq("audit"), anyString(), any(), eq(0L))).thenAnswer(i -> i.getArgument(2));
    audit.record(
        "JOB_LOG_READ",
        "job",
        "SUCCESS",
        mapper
            .createObjectNode()
            .put("pod", "jm")
            .put("container", "main")
            .put("lines", 100)
            .put("previous", true)
            .put("password", "must-not-persist"));
    var saved =
        org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.node.ObjectNode.class);
    verify(records).save(eq("audit"), anyString(), saved.capture(), eq(0L));
    assertThatCode(() -> java.time.Instant.parse(saved.getValue().path("time").asText()))
        .doesNotThrowAnyException();
    assertThat(saved.getValue().path("context").path("pod").asText()).isEqualTo("jm");
    assertThat(saved.getValue().toString()).doesNotContain("must-not-persist", "password");
  }
}
