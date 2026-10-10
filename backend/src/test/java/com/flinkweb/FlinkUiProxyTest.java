package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.flinkweb.config.platform.PlatformProperties;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.job.runtime.impl.FlinkUiServiceImpl;
import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.*;
import org.springframework.test.util.ReflectionTestUtils;

/** 验证 Flink UI 入口、子资源和错误的代理行为。 */
class FlinkUiProxyTest {
  /** 构造真实代理业务并仅替换 HTTP 传输。 */
  FlinkUiServiceImpl service(HttpClient client) {
    var jobs = mock(JobService.class);
    when(jobs.get("job-id"))
        .thenReturn(
            new JobDraft(
                "job-id",
                "display-name",
                "",
                "",
                "",
                0,
                Instant.now(),
                Instant.now(),
                7,
                "flink-0000007"));
    var service = new FlinkUiServiceImpl(jobs, new PlatformProperties("flink-ns", null, null));
    ReflectionTestUtils.setField(service, "client", client);
    return service;
  }

  /** 构造上游响应。 */
  @SuppressWarnings("unchecked")
  HttpResponse<InputStream> upstream(int status, String type, String body) {
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.headers())
        .thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of(type)), (a, b) -> true));
    when(response.body())
        .thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    return response;
  }

  /** 根地址映射到静态入口，保留编号服务及页面基址。 */
  @Test
  void rootRewritesBase() throws Exception {
    var client = mock(HttpClient.class);
    var upstream = upstream(200, "text/html", "<base href=\"/\"><app-root></app-root>");
    when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(upstream);
    var output = new MockHttpServletResponse();
    service(client).proxy("job-id", new MockHttpServletRequest("GET", "/flink-ui/job-id/"), output);
    var request = ArgumentCaptor.forClass(HttpRequest.class);
    verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
    assertThat(request.getValue().uri().toString())
        .isEqualTo("http://flink-0000007-rest.flink-ns.svc:8081/");
    assertThat(output.getContentAsString()).contains("<base href=\"/flink-ui/job-id/\">");
    assertThat(output.getStatus()).isEqualTo(200);
  }

  /** REST 子路径及查询参数不添加代理前缀，上游错误可以区分于传输错误。 */
  @Test
  void restQueryAndUpstreamErrorArePreserved() throws Exception {
    var client = mock(HttpClient.class);
    var upstream = upstream(500, "application/json", "{\"errors\":[\"Internal server error.\"]}");
    when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(upstream);
    var input = new MockHttpServletRequest("GET", "/flink-ui/job-id/jobs/overview");
    input.setQueryString("get=records");
    var output = new MockHttpServletResponse();
    service(client).proxy("job-id", input, output);
    var request = ArgumentCaptor.forClass(HttpRequest.class);
    verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
    assertThat(request.getValue().uri().getPath()).isEqualTo("/jobs/overview");
    assertThat(request.getValue().uri().getQuery()).isEqualTo("get=records");
    assertThat(output.getStatus()).isEqualTo(500);
    assertThat(output.getHeader("X-Flink-UI-Error")).isEqualTo("upstream");
  }

  /** 上游类元数据内存不足时，在入口展示可操作诊断而非笼统错误。 */
  @Test
  void metaspaceFailureExplainsRuntimeMemory() throws Exception {
    var client = mock(HttpClient.class);
    var upstream =
        upstream(500, "application/json", "{\"errors\":[\"Internal server error: Metaspace\"]}");
    when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(upstream);
    var output = new MockHttpServletResponse();
    service(client).proxy("job-id", new MockHttpServletRequest("GET", "/flink-ui/job-id/"), output);
    assertThat(output.getStatus()).isEqualTo(500);
    assertThat(output.getHeader("X-Flink-UI-Error")).isEqualTo("metaspace");
    assertThat(output.getContentAsString())
        .contains("jobmanager.memory.jvm-metaspace.size", "512m", "2048m");
  }
}
