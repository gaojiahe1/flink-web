package com.flinkweb.service.job.runtime.impl;

import com.flinkweb.config.platform.PlatformProperties;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.job.runtime.FlinkUiService;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 校验登录作业访问并转发 Flink UI 只读请求，限制路径及脱敏配置内容。 */
@Service
public class FlinkUiServiceImpl implements FlinkUiService {
  private final com.fasterxml.jackson.databind.ObjectMapper mapper =
      new com.fasterxml.jackson.databind.ObjectMapper();
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(FlinkUiServiceImpl.class);
  private final JobService jobs;
  private final String namespace;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  /** 注入草稿查询服务并读取固定 Namespace，限制代理目标范围。 */
  public FlinkUiServiceImpl(JobService jobs, PlatformProperties properties) {
    this.jobs = jobs;
    this.namespace = properties.namespace();
  }

  /** 重定向到带尾斜杠的 UI 地址，确保相对资源能从代理路径加载。 */
  public void trailingSlash(String id, HttpServletResponse response) throws IOException {
    jobs.get(id);
    response.sendRedirect("/flink-ui/" + id + "/");
  }

  /** 校验作业和请求路径，向集群内 REST Service 转发只读请求并处理配置脱敏。 */
  public void proxy(String id, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    var job = jobs.get(id);
    String prefix = "/flink-ui/" + id + "/",
        path = request.getRequestURI().substring(prefix.length());
    if (!path.matches("[a-zA-Z0-9_./-]*") || path.contains("..") || path.contains("//"))
      throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PROXY_PATH", "代理路径无效");
    if (path.matches(
        "(?i).*(^|/)(environment|logs|log|stdout|yarn-cancel|yarn-stop|cancel|stop|run|rescaling)(/|$).*"))
      throw new BusinessException(
          HttpStatus.FORBIDDEN, "SENSITIVE_ENDPOINT", "平台代理不开放可能泄露密钥的环境和原始日志接口");
    // 目标地址仅由已登记作业名和固定 Namespace 生成，不接受外部目标 URL。
    String host = job.deploymentName() + "-rest." + namespace + ".svc:8081";
    String query = request.getQueryString();
    String upstreamPath = path;
    URI target =
        URI.create("http://" + host + "/" + upstreamPath + (query == null ? "" : "?" + query));
    var outgoing =
        HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(30))
            .GET()
            .header("Accept", path.endsWith(".html") || path.isEmpty() ? "text/html" : "*/*")
            .build();
    try {
      var upstream = client.send(outgoing, HttpResponse.BodyHandlers.ofInputStream());
      try (var body = upstream.body()) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if (upstream.statusCode() >= 300 && upstream.statusCode() < 400) {
          String location = upstream.headers().firstValue("Location").orElse("/");
          URI redirect = target.resolve(location);
          if (!redirect.getAuthority().equals(host) || !redirect.getScheme().equals("http"))
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "UNSAFE_REDIRECT", "上游跳转目标不允许");
          response.sendRedirect(
              prefix
                  + redirect.getRawPath().replaceFirst("^/", "")
                  + (redirect.getRawQuery() == null ? "" : "?" + redirect.getRawQuery()));
          return;
        }
        if (upstream.statusCode() >= 400) {
          log.warn(
              "Flink UI upstream response jobId={} deployment={} path={} status={}",
              id,
              job.deploymentName(),
              upstreamPath,
              upstream.statusCode(),
              new IOException("Flink REST returned HTTP " + upstream.statusCode()));
          response.setHeader("X-Flink-UI-Error", "upstream");
        }
        response.setStatus(upstream.statusCode());
        String type =
            upstream.headers().firstValue("Content-Type").orElse("application/octet-stream");
        response.setContentType(type);
        if (upstream.statusCode() >= 500 && (path.isEmpty() || path.equals("index.html"))) {
          byte[] bytes = body.readNBytes(2 * 1024 * 1024 + 1);
          if (bytes.length > 2 * 1024 * 1024) throw new IOException("Error response too large");
          if (new String(bytes, StandardCharsets.UTF_8).contains("Metaspace")) {
            log.warn(
                "Flink UI unavailable: JobManager Metaspace exhausted jobId={} deployment={}",
                id,
                job.deploymentName(),
                new IOException("JobManager returned Metaspace error"));
            response.setHeader("X-Flink-UI-Error", "metaspace");
            response.setContentType("text/html;charset=UTF-8");
            response
                .getOutputStream()
                .write(
                    ("""
<!doctype html><html lang="zh-CN"><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Flink UI 暂不可用</title><body style="font-family:system-ui;max-width:720px;margin:64px auto;padding:24px;color:#24334b">
<h1>JobManager Metaspace 不足</h1><p>Flink 自身返回了内存错误，当前无法加载 Web UI。</p>
<p>请在作业 YAML 中检查 <code>jobmanager.memory.jvm-metaspace.size</code>。
可先调整为 <code>512m</code>，并将 JobManager 总内存调整为 <code>2048m</code>，为堆和其他内存留出空间。</p>
<p>保存配置并重新部署后生效；持续出现时，请检查 JobManager 日志与类加载情况。</p>
<a href="/">返回平台</a></body></html>
""")
                        .getBytes(StandardCharsets.UTF_8));
            return;
          }
          response.getOutputStream().write(bytes);
          return;
        }
        if (path.endsWith("/config")
            && !path.matches("(v[0-9]+/)?config")
            && upstream.statusCode() == 200) {
          byte[] bytes = body.readNBytes(2 * 1024 * 1024 + 1);
          if (bytes.length > 2 * 1024 * 1024) throw new IOException("Config too large");
          response.setContentType("application/json");
          mapper.writeValue(
              response.getOutputStream(), redactConfig(mapper.readTree(bytes), false));
        } else if (type.contains("text/html")) {
          byte[] bytes = body.readNBytes(2 * 1024 * 1024 + 1);
          if (bytes.length > 2 * 1024 * 1024) throw new IOException("HTML too large");
          String html =
              new String(bytes, StandardCharsets.UTF_8)
                  .replace("<base href=\"/\">", "<base href=\"" + prefix + "\">")
                  .replace("<base href=\"./\">", "<base href=\"" + prefix + "\">");
          response.getOutputStream().write(html.getBytes(StandardCharsets.UTF_8));
        } else body.transferTo(response.getOutputStream());
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.warn(
          "Flink UI proxy interrupted jobId={} deployment={} path={}",
          id,
          job.deploymentName(),
          upstreamPath,
          ex);
      throw unavailable(ex);
    } catch (IOException ex) {
      log.warn(
          "Flink UI proxy transport failed jobId={} deployment={} path={} errorType={}",
          id,
          job.deploymentName(),
          upstreamPath,
          ex.getClass().getSimpleName(),
          ex);
      if (!response.isCommitted()) throw unavailable(ex);
    }
  }

  /** 保留配置响应结构并屏蔽参数值，避免凭据随 Flink UI 暴露。 */
  static com.fasterxml.jackson.databind.JsonNode redactConfig(
      com.fasterxml.jackson.databind.JsonNode node, boolean sensitive) {
    if (node.isObject()) {
      var copy = ((com.fasterxml.jackson.databind.node.ObjectNode) node).deepCopy();
      var keys = new java.util.ArrayList<String>();
      copy.fieldNames().forEachRemaining(keys::add);
      for (String key : keys) {
        boolean privateField = sensitive || key.equals("user-config") || key.equals("userConfig");
        if (privateField
            || !java.util.Set.of(
                    "jid", "name", "key", "execution-mode", "job-parallelism", "object-reuse-mode")
                .contains(key)) copy.set(key, redactConfig(copy.get(key), privateField));
      }
      return copy;
    }
    if (node.isArray()) {
      var copy = ((com.fasterxml.jackson.databind.node.ArrayNode) node).deepCopy();
      for (int i = 0; i < copy.size(); i++) copy.set(i, redactConfig(copy.get(i), sensitive));
      return copy;
    }
    return com.fasterxml.jackson.databind.node.TextNode.valueOf("[REDACTED]");
  }

  /** 生成不泄露内部网络细节的代理不可用异常。 */
  private BusinessException unavailable(Throwable cause) {
    return new BusinessException(
        HttpStatus.BAD_GATEWAY,
        "FLINK_UI_UNAVAILABLE",
        "Flink UI 不可访问，请确认 JobManager 和 REST Service 已就绪",
        cause);
  }
}
