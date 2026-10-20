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
  private final AiSharedSummary memory;
  private final org.springframework.transaction.support.TransactionTemplate transaction;

  /** 复用现有元数据仓库与进程内事件，不保留另一份任务或凭据缓存。 */
  public AiJobState(RecordRepository records, ApplicationEventPublisher events,AiSharedSummary memory,org.springframework.transaction.PlatformTransactionManager manager) {
    this.memory=memory;this.transaction=new org.springframework.transaction.support.TransactionTemplate(manager);
    this.records = records; this.events = events;
  }

  /** 所有生命周期写入共用短期身份锁；模型网络调用不持此锁。 */
  public Object monitor() { return monitor; }

  /** 删除全部用户的作业 AI 正文及索引，并中断仍在运行的本地任务。 */
  public void delete(String jobId) {
    for (String collection : List.of("ai_job_conversations", "ai_job_evidence", "ai_job_plans", "ai_job_applications", "ai_tasks", "ai_summaries", "ai_job_runtime"))
      for (var record : records.list(collection))
        if (jobId.equals(record.path("jobId").asText()))
          records.delete(collection, record.path("id").asText(), record.path("revision").asLong());
    events.publishEvent(new Deleted(jobId));
  }

  /** 通知模型任务中断；已删除记录不得由迟到回调重新创建。 */
  public record Deleted(String jobId) {}

  /** 仅已核验身份且当前修订实际稳定运行时清理旧排障，保留唯一总结和SQL创作对话。 */
  public void observed(com.fasterxml.jackson.databind.node.ObjectNode row) {
    if(!row.path("managed").asBoolean() || row.path("deleting").asBoolean()
        || !row.path("runningRevisionConfirmed").asBoolean() || !row.path("jobState").asText().equals("RUNNING")
        || !row.path("healthState").asText().equals("RUNNING") || !row.path("lifecycleState").asText().equals("STABLE")
        || row.path("runIdentity").asText().isBlank() || row.path("desiredState").asText().equals("stopped")) return;
    String jobId=row.path("jobId").asText(),run=row.path("runIdentity").asText();
    synchronized(monitor) {
      var previous=records.find("ai_job_runtime",jobId);
      if(previous!=null && run.equals(previous.path("runIdentity").asText()))return;
      for(var application:records.listForSubject("ai_job_applications",jobId)) {
        String state=application.path("state").asText();
        var operation=records.find("operations",application.path("operationId").asText());
        if(operation!=null)state=operation.path("state").asText();
        if(List.of("SAVING","SUBMITTING","UNKNOWN").contains(state)
            || (state.equals("SAVED") && application.path("mode").asText().equals("restart"))) return;
      }
      var conversations=records.listForSubject("ai_job_conversations",jobId);
      transaction.executeWithoutResult(status->{
        if(!conversations.isEmpty())memory.job(jobId);
        for(var conversation:conversations) {
          if(run.equals(conversation.path("observedRunId").asText()))continue;
          conversation.putArray("messages");conversation.remove(List.of("lastPlanId","lastTaskId","summary","unknowns"));
          conversation.put("observedRunId",run).put("contentVersion",conversation.path("contentVersion").asLong()+1).put("compressedMessages",0);
          records.save("ai_job_conversations",conversation.path("id").asText(),conversation,conversation.path("revision").asLong());
        }
        for(String collection:List.of("ai_job_evidence","ai_job_plans","ai_job_applications","ai_tasks"))
          for(var record:records.listForSubject(collection,jobId)) records.delete(collection,record.path("id").asText(),record.path("revision").asLong());
        events.publishEvent(new Deleted(jobId));
        var marker=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("jobId",jobId).put("runIdentity",run);
        records.save("ai_job_runtime",jobId,marker,previous==null?0:previous.path("revision").asLong());
      });
    }
  }
}
