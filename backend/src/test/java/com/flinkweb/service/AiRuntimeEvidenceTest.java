package com.flinkweb.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.security.permission.SecretCipher;
import com.flinkweb.service.ai.support.AiJobEvidenceDetails;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.job.management.JobOwnershipService;
import com.flinkweb.service.job.runtime.impl.DiagnosticServiceImpl;
import com.flinkweb.service.permission.AccessService;
import com.flinkweb.service.session.SessionClusterService;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** 覆盖Flink证据新增的Secret边界与关键日志摘录，测试不连接集群或外部服务。 */
class AiRuntimeEvidenceTest {
  /** 多层JSON叶子中的引用Secret值必须遮蔽，外部Pod的Secret不得读取。 */
  @Test
  void masksReferencedSecretsAndIgnoresForeignPods() throws Exception {
    var mapper=new ObjectMapper();var client=mock(KubernetesClient.class);var jobs=mock(JobService.class);var job=mock(JobDraft.class);
    when(jobs.get("job")).thenReturn(job);when(job.deploymentName()).thenReturn("flink-one");when(client.core()).thenReturn("/api/v1/namespaces/team");
    var deployment=mapper.readTree("{\"metadata\":{\"uid\":\"owned\"},\"spec\":{\"podTemplate\":{\"spec\":{\"volumes\":[{\"secret\":{\"secretName\":\"business\"}}]}}}}");
    when(client.require("flink-one")).thenReturn((com.fasterxml.jackson.databind.node.ObjectNode)deployment);
    var foreign=mapper.readTree("{\"items\":[{\"metadata\":{\"name\":\"foreign\"},\"spec\":{\"volumes\":[{\"secret\":{\"secretName\":\"not-owned\"}}]}}]}");
    when(client.call(eq("GET"),contains("/pods?"),isNull())).thenReturn(foreign);
    String secret="secret-value-with-quote-\"-and-backslash-\\";
    when(client.call("GET","/api/v1/namespaces/team/secrets/business",null)).thenReturn(mapper.createObjectNode().set("data",mapper.createObjectNode().put("key",Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)))));
    var service=new DiagnosticServiceImpl(client,jobs,mock(JobOwnershipService.class),mock(AccessService.class),mapper,mock(RecordRepository.class),mock(SecretCipher.class),mock(SessionClusterService.class));
    var input=mapper.createObjectNode().put("error","Failed: "+secret);input.putArray("stack").add(secret);
    var safe=service.redactEvidence("job",input);
    assertThat(safe.path("error").asText()).isEqualTo("Failed: [REDACTED]");assertThat(safe.path("stack").get(0).asText()).isEqualTo("[REDACTED]");assertThat(input.path("error").asText()).contains(secret);
    verify(client,never()).call("GET","/api/v1/namespaces/team/secrets/not-owned",null);
  }

  /** 摘录保留关键异常及其邻近因果栈，避免只截最新尾部漏掉早一点的根因。 */
  @Test
  void logExcerptIncludesEarlyRootCauseAndLatestOutput() {
    var log=new StringBuilder("INFO starting\nERROR source failed\nCaused by: missing connector option\n");for(int i=0;i<300;i++)log.append("INFO running ").append(i).append('\n');
    String result=AiJobEvidenceDetails.excerpt(log.toString());
    assertThat(result).contains("ERROR source failed","missing connector option","INFO running 299").doesNotContain("INFO running 150");
  }
}
