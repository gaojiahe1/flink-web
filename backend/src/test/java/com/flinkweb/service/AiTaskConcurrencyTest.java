package com.flinkweb.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.client.ai.AiModelClient;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.ai.*;
import com.flinkweb.service.ai.impl.AiTaskServiceImpl;
import com.flinkweb.service.ai.support.*;
import com.flinkweb.service.audit.AuditService;
import com.flinkweb.service.datasource.DataSourceService;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.job.runtime.DiagnosticService;
import com.flinkweb.service.permission.AccessService;
import com.flinkweb.service.sql.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 在真实满队列与完成路径交错时验证锁顺序，所有工作线程均有界退出。 */
class AiTaskConcurrencyTest {
  /** 创建线程持作业锁时，完成线程不得先持任务锁而阻塞拒绝路径。 */
  @Test
  void fullQueueRejectionAndCompletionDoNotInvertLocks() throws Exception {
    var mapper=new ObjectMapper();var records=mock(RecordRepository.class);
    var state=mock(AiJobState.class);var monitor=new Object();when(state.monitor()).thenReturn(monitor);
    var models=mock(AiModelService.class);var access=mock(AccessService.class);
    var jobs=mock(AiJobService.class);
    when(access.actor()).thenReturn("creator");
    when(models.require(anyString(),anyString())).thenReturn(mapper.createObjectNode().put("external",false));
    var data=new ConcurrentHashMap<String,ObjectNode>();
    when(records.find(eq("ai_tasks"),anyString())).thenAnswer(c->{var n=data.get(c.getArgument(1));return n==null?null:n.deepCopy();});
    when(records.require(eq("ai_tasks"),anyString())).thenAnswer(c->data.get(c.getArgument(1)).deepCopy());
    when(records.save(eq("ai_tasks"),anyString(),any(),anyLong())).thenAnswer(c->{
      var n=c.getArgument(2,ObjectNode.class).deepCopy().put("id",c.getArgument(1,String.class)).put("revision",c.getArgument(3,Long.class)+1);
      data.put(c.getArgument(1),n);return n.deepCopy();
    });
    doAnswer(c->{synchronized(monitor){c.getArgument(5,Runnable.class).run();}return null;})
        .when(jobs).complete(anyString(),anyLong(),anyString(),any(),any(),any());
    var service=new AiTaskServiceImpl(records,mapper,models,mock(AiModelClient.class),access,
        mock(AuditService.class),mock(DataSourceService.class),mock(DiagnosticService.class),mock(JobService.class),
        mock(SqlJobService.class),mock(SqlValidationService.class),mock(AiContentGuard.class),mock(AiConversationService.class),jobs,state);
    var pool=(ThreadPoolExecutor)ReflectionTestUtils.getField(service,"executor");
    var occupied=new CountDownLatch(2);var release=new CountDownLatch(1);
    Runnable blocker=()->{occupied.countDown();try {release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}};
    try {
      pool.execute(blocker);pool.execute(blocker);assertThat(occupied.await(2,TimeUnit.SECONDS)).isTrue();
      for(int i=0;i<6;i++)pool.execute(()->{});
      String completed=UUID.randomUUID().toString();
      data.put(completed,mapper.createObjectNode().put("owner","creator").put("state","RUNNING").put("revision",1).put("jobId","job").put("jobConversationVersion",1));
      var locked=new CountDownLatch(1);var proceed=new CountDownLatch(1);
      var created=new CompletableFuture<ObjectNode>();var failure=new AtomicReference<Throwable>();
      var creator=new Thread(()->{
        try {synchronized(monitor){locked.countDown();proceed.await(3,TimeUnit.SECONDS);
          created.complete(service.create(mapper.createObjectNode().put("kind","definition").put("requestId",UUID.randomUUID().toString())));}}
        catch(Throwable e){created.completeExceptionally(e);}
      });creator.setDaemon(true);creator.start();assertThat(locked.await(2,TimeUnit.SECONDS)).isTrue();
      var finisher=new Thread(()->{try {ReflectionTestUtils.invokeMethod(service,"finish",completed,"SUCCEEDED","done",mapper.createObjectNode(),mapper.createObjectNode());}catch(Throwable e){failure.set(e);}});
      finisher.setDaemon(true);finisher.start();
      org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).until(()->finisher.getState()==Thread.State.BLOCKED);
      proceed.countDown();
      assertThat(created.get(2,TimeUnit.SECONDS).path("state").asText()).isEqualTo("FAILED");
      finisher.join(2000);assertThat(finisher.isAlive()).isFalse();assertThat(failure.get()).isNull();
      assertThat(data.get(completed).path("state").asText()).isEqualTo("SUCCEEDED");
      release.countDown();var next=new CountDownLatch(1);pool.execute(next::countDown);
      assertThat(next.await(2,TimeUnit.SECONDS)).isTrue();
    } finally {release.countDown();service.close();}
  }
}
