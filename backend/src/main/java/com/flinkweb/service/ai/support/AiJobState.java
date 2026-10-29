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
    events.publishEvent(new Purged(jobId));
  }

  /** 通知模型任务中断；已删除记录不得由迟到回调重新创建。 */
  public record Deleted(String jobId) {}

  /** 只有作业实际删除才清理独立会话；旧助手轮次重置不属于领域删除。 */
  public record Purged(String jobId) {}

  /** SQL 草稿删除时通知独立 Harness 清理，调用方已持生命周期锁与短事务。 */
  public void sqlDeleted(String sqlId) { events.publishEvent(new SqlDeleted(sqlId)); }

  /** 同名新草稿不能复用已删除草稿的 Harness 数据。 */
  public record SqlDeleted(String sqlId) {}

  /** 重启受理或实际保存内容改变即结束旧轮次；未知应用保留原号恢复入口。 */
  public com.fasterxml.jackson.databind.node.ObjectNode synchronize(String jobId,com.fasterxml.jackson.databind.node.ObjectNode baseline) {
    synchronized(monitor) {
      var previous=records.find("ai_job_runtime",jobId);
      for(var application:records.listForSubject("ai_job_applications",jobId)) {
        String status=application.path("state").asText();
        var operation=records.find("operations",application.path("operationId").asText());
        if(operation!=null)status=operation.path("state").asText();
        if(List.of("SAVING","SUBMITTING","UNKNOWN").contains(status)
            || (status.equals("SAVED") && application.path("mode").asText().equals("restart"))) return previous;
      }
      var accepted=records.listForSubject("operations",jobId).stream()
          .filter(r->"ACCEPTED".equals(r.path("state").asText()) && List.of("deploy","start","restart","restore").contains(r.path("action").asText()))
          .max(java.util.Comparator.comparing(r->java.time.Instant.parse(r.path("createdAt").asText()))).orElse(null);
      String operationId=accepted==null?"":accepted.path("id").asText();
      var basis=baseline.deepCopy().without(List.of("jobRevision","sqlRevision"));
      boolean changed=previous!=null && previous.has("basis")
          && (!basis.equals(previous.path("basis")) || !operationId.equals(previous.path("operationId").asText()));
      var conversations=records.listForSubject("ai_job_conversations",jobId);
      if(previous==null || !previous.has("basis")) {
        for(var value:conversations) {
          var old=records.find("ai_job_evidence",value.path("id").asText());
          if(old!=null && old.path("baseline").isObject() && !basis.equals(((com.fasterxml.jackson.databind.node.ObjectNode)old.path("baseline")).deepCopy().without(List.of("jobRevision","sqlRevision"))))changed=true;
          if(accepted!=null && java.time.Instant.parse(accepted.path("createdAt").asText()).isAfter(java.time.Instant.parse(value.path("updatedAt").asText())))changed=true;
        }
      } else if(!changed) return previous;
      final boolean reset=changed;
      final var oldMarker=previous;
      return transaction.execute(status->{
        if(reset) {
          if(!conversations.isEmpty())memory.job(jobId);
          for(var value:conversations) {
            value.putArray("messages");value.remove(List.of("lastPlanId","lastTaskId","summary","unknowns"));
            value.put("contentVersion",value.path("contentVersion").asLong()+1).put("compressedMessages",0);
            records.save("ai_job_conversations",value.path("id").asText(),value,value.path("revision").asLong());
          }
          for(var record:records.listForSubject("ai_job_evidence",jobId)) records.delete("ai_job_evidence",record.path("id").asText(),record.path("revision").asLong());
          // 已应用方案和终态应用保留有限条用于同号恢复，不再通过当前轮次展示。
          for(var task:records.listForSubject("ai_tasks",jobId))
            if(List.of("QUEUED","RUNNING").contains(task.path("state").asText())) records.save("ai_tasks",task.path("id").asText(),task.put("state","CANCELED").put("message","作业已重启或配置变化，旧轮次已结束"),task.path("revision").asLong());
          events.publishEvent(new Deleted(jobId));
        }
        var marker=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("jobId",jobId)
            .put("roundId",java.util.UUID.randomUUID().toString()).put("startedAt",java.time.Instant.now().toString())
            .put("operationId",operationId).put("reason",reset?"作业已重新提交或 SQL / 配置已更新，已开始新一轮排障；历史总结仅作参考。":"");
        marker.set("basis",basis);
        return records.save("ai_job_runtime",jobId,marker,oldMarker==null?0:oldMarker.path("revision").asLong());
      });
    }
  }

}
