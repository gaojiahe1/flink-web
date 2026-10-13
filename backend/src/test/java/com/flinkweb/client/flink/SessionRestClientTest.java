package com.flinkweb.client.flink;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.config.platform.PlatformProperties;
import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** 固定验证文件路由、传输限制及缺失轮转副本的兼容性。 */
class SessionRestClientTest {
  /** 校验只允许两个固定文件，普通日志与路径穿越不能通过。 */
  @Test
  @SuppressWarnings("unchecked")
  void validationFileUsesNamedLogRouteAndAllowsAbsentBackup() throws Exception {
    var client = mock(HttpClient.class);
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body())
        .thenReturn(new ByteArrayInputStream("验证数据\n".getBytes(StandardCharsets.UTF_8)));
    when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    var rest =
        new SessionRestClient(new ObjectMapper(), new PlatformProperties("flink-ns", null, null));
    ReflectionTestUtils.setField(rest, "client", client);
    assertThat(rest.validationLog("fw-session-sql-validation", "tm-1", "sql-validation.log"))
        .isEqualTo("验证数据\n");
    var request = ArgumentCaptor.forClass(HttpRequest.class);
    verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
    assertThat(request.getValue().uri().toString())
        .isEqualTo(
            "http://fw-session-sql-validation-rest.flink-ns.svc:8081/taskmanagers/tm-1/logs/sql-validation.log");
    assertThatThrownBy(() -> rest.validationLog("fw-session-sql-validation", "tm-1", "../config"))
        .hasMessageContaining("标识无效");
    when(response.statusCode()).thenReturn(404);
    when(response.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
    assertThat(rest.validationLog("fw-session-sql-validation", "tm-1", "sql-validation.log.1"))
        .isEmpty();
  }

  /** 尾部缓存保持固定大小，超限响应明确拒绝，不吞掉通信故障。 */
  @Test
  void tailIsBoundedAndOversizedStreamRejected() throws Exception {
    assertThat(
            SessionRestClient.readTail(
                new ByteArrayInputStream("abcdef".getBytes(StandardCharsets.UTF_8)), 4, 10))
        .isEqualTo("cdef");
    assertThatThrownBy(
            () -> SessionRestClient.readTail(new ByteArrayInputStream(new byte[11]), 4, 10))
        .hasMessageContaining("128 MiB");
  }
}
