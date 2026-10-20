package com.flinkweb.service.ai.support;

import com.flinkweb.repository.metadata.RecordRepository;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** 单后端实例中串行保护作业 AI 的追加、完成、应用和生命周期删除。 */
@Component
public class AiJobState {
  private final Object monitor = new Object();
  private final RecordRepository records;
  private final ApplicationEventPublisher events;

  /** 复用现有元数据仓库与进程内事件，不保留另一份任务或凭据缓存。 */
  public AiJobState(RecordRepository records, ApplicationEventPublisher events) {
    this.records = records; this.events = events;
  }

  /** 所有生命周期写入共用短期身份锁；模型网络调用不持此锁。 */
  public Object monitor() { return monitor; }

  /** 删除全部用户的作业 AI 正文及索引，并中断仍在运行的本地任务。 */
  public void delete(String jobId) {
    for (String collection : List.of("ai_job_conversations", "ai_job_evidence", "ai_job_plans", "ai_job_applications", "ai_tasks"))
      for (var record : records.list(collection))
        if (jobId.equals(record.path("jobId").asText()))
          records.delete(collection, record.path("id").asText(), record.path("revision").asLong());
    events.publishEvent(new Deleted(jobId));
  }

  /** 通知模型任务中断；已删除记录不得由迟到回调重新创建。 */
  public record Deleted(String jobId) {}
}
