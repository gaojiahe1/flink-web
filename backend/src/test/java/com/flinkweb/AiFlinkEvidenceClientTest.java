package com.flinkweb;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.client.flink.AiFlinkEvidenceClient;
import com.flinkweb.config.platform.PlatformProperties;
import java.io.ByteArrayInputStream;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 真实端点白名单与响应预算回归，HTTP客户端使用协议替身。 */
class AiFlinkEvidenceClientTest {
  /** 允许Checkpoint配置、长整型ID和算子详情，拒绝任意地址和变更端点。 */
  @Test
  void explicitReadPathsAndLongCheckpointIds()throws Exception{
    var properties=mock(PlatformProperties.class);when(properties.namespace()).thenReturn("test");var client=new AiFlinkEvidenceClient(properties,new ObjectMapper());var http=mock(HttpClient.class);
    org.springframework.test.util.ReflectionTestUtils.setField(client,"client",http);
    when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(inv->{var response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn(new ByteArrayInputStream("{\"ok\":true}".getBytes(StandardCharsets.UTF_8)));return response;});
    String job="a".repeat(32),vertex="b".repeat(32);
    for(String path:new String[]{"jobs/"+job+"/checkpoints/config","jobs/"+job+"/checkpoints/details/9999999999","jobs/"+job+"/checkpoints/details/12/subtasks/"+vertex,"taskmanagers/tm:6122/log"})assertThat(client.read("flink-job",path)).isNotNull();
    for(String path:new String[]{"https://evil.example/","jobs/"+job+"/savepoints","jobs/"+job+"/checkpoints/details/1/../../config","taskmanagers/tm/log?token=x","jobmanager/config","jars"})assertThatThrownBy(()->client.read("flink-job",path)).isInstanceOf(IllegalArgumentException.class);
    verify(http,times(4)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
  }
  /** Flink文件日志保留更大尾部，普通JSON仍拒绝超限响应。 */
  @Test
  void logTailAndJsonBudgetsAreIndependent()throws Exception{
    var properties=mock(PlatformProperties.class);when(properties.namespace()).thenReturn("test");var client=new AiFlinkEvidenceClient(properties,new ObjectMapper());var http=mock(HttpClient.class);org.springframework.test.util.ReflectionTestUtils.setField(client,"client",http);
    byte[] data="x".repeat(100000).getBytes(StandardCharsets.UTF_8);
    when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(inv->{var response=mock(HttpResponse.class);when(response.statusCode()).thenReturn(200);when(response.body()).thenReturn(new ByteArrayInputStream(data));return response;});
    assertThat(client.read("flink-job","jobmanager/log").path("text").asText()).hasSize(100000);
    assertThatThrownBy(()->client.read("flink-job","overview")).hasMessageContaining("预算");
  }
}
