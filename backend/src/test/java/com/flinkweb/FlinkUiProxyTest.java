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
    var sessions = mock(com.flinkweb.service.session.SessionClusterService.class);
    var resource = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
    resource.withObject("/metadata").put("name", "fw-session-sql-validation");
    when(sessions.observedResource("sql-validation")).thenReturn(resource);
    var service =
        new FlinkUiServiceImpl(
            jobs,
            new PlatformProperties("flink-ns", null, null),
            sessions,
            mock(com.flinkweb.service.permission.AccessService.class),
            mock(com.flinkweb.service.job.runtime.DiagnosticService.class),
            mock(com.flinkweb.service.ai.support.AiContentGuard.class));
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

  /** Session 目标固定且重写基路径，敏感接口沿用同一只读限制。 */
  @Test
  void sessionUsesVerifiedTargetAndRestrictsSensitivePaths() throws Exception {
    var client = mock(HttpClient.class);
    var upstream = upstream(200, "text/html", "<base href=\"/\">");
    when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(upstream);
    var service = service(client);
    var output = new MockHttpServletResponse();
    service.sessionProxy(
        "sql-validation",
        new MockHttpServletRequest("GET", "/flink-ui/sessions/sql-validation/"),
        output);
    var request = ArgumentCaptor.forClass(HttpRequest.class);
    verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
    assertThat(request.getValue().uri().toString())
        .isEqualTo("http://fw-session-sql-validation-rest.flink-ns.svc:8081/");
    assertThat(output.getContentAsString())
        .contains("<base href=\"/flink-ui/sessions/sql-validation/\">");
    assertThatThrownBy(
            () ->
                service.sessionProxy(
                    "sql-validation",
                    new MockHttpServletRequest(
                        "GET",
                        "/flink-ui/sessions/sql-validation/taskmanagers/tm/logs/sql-validation.log"),
                    new MockHttpServletResponse()))
        .hasMessageContaining("日志仅向");
    var redirect = new MockHttpServletResponse();
    service.sessionTrailingSlash("sql-validation", redirect);
    assertThat(redirect.getRedirectedUrl()).isEqualTo("/flink-ui/sessions/sql-validation/");
  }

  /** JM/TM 日志按角色与资源授权读取，保持版本/端口 ID，返回脱敏结果。 */
  @Test
  void authorizedLogsAreBoundedAndRedacted() throws Exception {
    var client = mock(HttpClient.class);
    var service = service(client);
    var diagnostics =
        (com.flinkweb.service.job.runtime.DiagnosticService)
            ReflectionTestUtils.getField(service, "diagnostics");
    var guard =
        (com.flinkweb.service.ai.support.AiContentGuard)
            ReflectionTestUtils.getField(service, "guard");
    when(diagnostics.redactEvidence(eq("job-id"), any()))
        .thenAnswer(
            i ->
                new com.fasterxml.jackson.databind.node.TextNode(
                    i.getArgument(1, com.fasterxml.jackson.databind.JsonNode.class)
                        .asText()
                        .replace("k8s-value", "[REDACTED]")));
    when(guard.sanitize(any())).thenAnswer(i -> i.getArgument(0));
    var auth =
        new org.springframework.security.authentication.TestingAuthenticationToken(
            "operator", "", "ROLE_OPERATOR");
    org.springframework.security.core.context.SecurityContextHolder.getContext()
        .setAuthentication(auth);
    try {
      for (String path : List.of("jobmanager/log", "taskmanagers/tm-id%3A6122/logs/task.log.1")) {
        var logResponse = upstream(200, "text/plain", "startup k8s-value");
        when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(logResponse);
        var out = new MockHttpServletResponse();
        service.proxy("job-id", new MockHttpServletRequest("GET", "/flink-ui/job-id/" + path), out);
        assertThat(out.getContentAsString()).isEqualTo("startup [REDACTED]");
        assertThat(out.getHeader("Cache-Control")).isEqualTo("no-store");
      }
      verify(
              (com.flinkweb.service.permission.AccessService)
                  ReflectionTestUtils.getField(service, "access"),
              times(2))
          .require("job-id", "operate");
      var huge = upstream(200, "text/plain", "x".repeat(10 * 1024 * 1024 + 1));
      when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(huge);
      assertThatThrownBy(
              () ->
                  service.proxy(
                      "job-id",
                      new MockHttpServletRequest("GET", "/flink-ui/job-id/jobmanager/log"),
                      new MockHttpServletResponse()))
          .hasMessageContaining("超过 10 MB");
    } finally {
      org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
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

  /** 已启动的 Session 也隐藏提交入口，禁止通过代理探查 JAR，作业代理配置不改写。 */
  @Test
  void sessionDisablesSubmissionInConfigAndJarPaths() throws Exception {
    var client = mock(HttpClient.class);
    var service = service(client);
    for (String path : List.of("config", "v1/config")) {
      var configResponse =
          upstream(
              200,
              "application/json",
              "{\"features\":{\"web-submit\":true,\"web-cancel\":true,\"web-history\":true},\"flink-version\":\"1.20.5\"}");
      when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(configResponse);
      var output = new MockHttpServletResponse();
      service.sessionProxy(
          "sql-validation",
          new MockHttpServletRequest("GET", "/flink-ui/sessions/sql-validation/" + path),
          output);
      var result =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readTree(output.getContentAsByteArray());
      assertThat(result.at("/features/web-submit").asBoolean()).isFalse();
      assertThat(result.at("/features/web-cancel").asBoolean()).isFalse();
      assertThat(result.at("/features/web-submit").isBoolean()).isTrue();
      assertThat(result.at("/features/web-history").asBoolean()).isTrue();
      assertThat(result.path("flink-version").asText()).isEqualTo("1.20.5");
    }
    for (String path : List.of("jars", "jars/test.jar", "v1/jars")) {
      assertThatThrownBy(
              () ->
                  service.sessionProxy(
                      "sql-validation",
                      new MockHttpServletRequest(
                          "GET", "/flink-ui/sessions/sql-validation/" + path),
                      new MockHttpServletResponse()))
          .isInstanceOf(com.flinkweb.exception.common.BusinessException.class)
          .hasMessageContaining("提交");
    }
    var applicationConfig = upstream(200, "application/json", "{\"web-submit\":true}");
    when(client.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(applicationConfig);
    var output = new MockHttpServletResponse();
    service.proxy("job-id", new MockHttpServletRequest("GET", "/flink-ui/job-id/config"), output);
    assertThat(output.getContentAsString()).contains("true");
  }
}
