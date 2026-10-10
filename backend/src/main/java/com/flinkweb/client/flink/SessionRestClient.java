package com.flinkweb.client.flink;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.config.platform.PlatformProperties;
import com.flinkweb.exception.common.BusinessException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 只连接固定 Namespace 中由平台登记的 Session REST Service。 */
@Component
public class SessionRestClient {
  private final ObjectMapper mapper;
  private final String namespace;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  /** 注入平台 Namespace，不接受浏览器传入上游地址。 */
  public SessionRestClient(ObjectMapper mapper, PlatformProperties properties) {
    this.mapper = mapper;
    this.namespace = properties.namespace();
  }

  /** 构建并验证内部 REST 地址。 */
  private URI target(String deployment, String path) {
    if (!deployment.matches("fw-session-[a-z0-9-]{1,50}")
        || !path.startsWith("/")
        || path.contains(".."))
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "INVALID_SESSION_PATH", "Session REST 路径无效");
    return URI.create("http://" + deployment + "-rest." + namespace + ".svc:8081" + path);
  }

  /** 读取受限长度 REST JSON，保留失败摘要供服务层脱敏。 */
  public JsonNode call(String deployment, String method, String path, JsonNode body) {
    var request =
        HttpRequest.newBuilder(target(deployment, path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
    String response = send(request, 1024 * 1024);
    try {
      return response.isBlank() ? mapper.createObjectNode() : mapper.readTree(response);
    } catch (Exception ex) {
      throw unavailable("Flink REST 返回无效响应");
    }
  }

  /** 使用流式 multipart 上传本次 Runner 与依赖包，避免一次性读入内存。 */
  public ObjectNode upload(String deployment, Path jar) {
    String boundary = "fw" + java.util.UUID.randomUUID().toString().replace("-", "");
    try {
      var prefix =
          HttpRequest.BodyPublishers.ofString(
              "--"
                  + boundary
                  + "\r\n"
                  + "Content-Disposition: form-data; name=\"jarfile\";"
                  + " filename=\"sql-validation.jar\"\r\n"
                  + "Content-Type: application/java-archive\r\n\r\n");
      var body =
          HttpRequest.BodyPublishers.concat(
              prefix,
              HttpRequest.BodyPublishers.ofFile(jar),
              HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"));
      var request =
          HttpRequest.newBuilder(target(deployment, "/jars/upload"))
              .timeout(Duration.ofSeconds(60))
              .header("Content-Type", "multipart/form-data; boundary=" + boundary)
              .POST(body)
              .build();
      return (ObjectNode) mapper.readTree(send(request, 65536));
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw unavailable("无法上传 SQL 验证依赖");
    }
  }

  /** 流式读取 TaskManager 日志尾部；Flink 1.20 忽略 Range，兼容完整文件响应。 */
  public String log(String deployment, String taskmanager) {
    if (!taskmanager.matches("[A-Za-z0-9_.-]{1,200}")) throw unavailable("TaskManager 标识无效");
    var request =
        HttpRequest.newBuilder(target(deployment, "/taskmanagers/" + taskmanager + "/log"))
            .timeout(Duration.ofSeconds(15))
            .header("Range", "bytes=-524288")
            .GET()
            .build();
    try {
      var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (var stream = response.body()) {
        if (response.statusCode() < 200 || response.statusCode() >= 300)
          throw unavailable("无法读取 TaskManager 日志 (HTTP " + response.statusCode() + ")");
        return readTail(stream, 524288, 128L * 1024 * 1024);
      }
    } catch (BusinessException ex) {
      throw ex;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw unavailable("日志请求已中断");
    } catch (Exception ex) {
      throw unavailable("无法读取 Session 日志，请检查集群和日志配置");
    }
  }

  /** 环形缓冲仅保留最后一段日志，内存固定；总读取量受限，防止无限上游流。 */
  static String readTail(InputStream stream, int capacity, long maximum)
      throws java.io.IOException {
    byte[] tail = new byte[capacity], chunk = new byte[8192];
    long total = 0;
    int position = 0, size = 0, count;
    while ((count = stream.read(chunk)) != -1) {
      total += count;
      if (total > maximum)
        throw new BusinessException(
            HttpStatus.BAD_GATEWAY,
            "SESSION_LOG_TOO_LARGE",
            "Session 日志超过 128 MiB，请配置日志轮转或重启专用验证集群");
      int start = Math.max(0, count - capacity), remaining = count - start;
      while (remaining > 0) {
        int length = Math.min(remaining, capacity - position);
        System.arraycopy(chunk, start, tail, position, length);
        position = (position + length) % capacity;
        start += length;
        remaining -= length;
        size = Math.min(capacity, size + length);
      }
    }
    byte[] ordered = new byte[size];
    int first = size == capacity ? position : 0, length = Math.min(size, capacity - first);
    System.arraycopy(tail, first, ordered, 0, length);
    System.arraycopy(tail, 0, ordered, length, size - length);
    return new String(ordered, StandardCharsets.UTF_8);
  }

  /** 统一关闭响应流、限制正文大小，并保留中断语义。 */
  private String send(HttpRequest request, int limit) {
    try {
      var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (var stream = response.body()) {
        byte[] bytes = stream.readNBytes(limit + 1);
        if (bytes.length > limit) throw unavailable("Flink 响应过大，请检查 Session 日志大小");
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (response.statusCode() < 200 || response.statusCode() >= 300)
          throw new BusinessException(
              HttpStatus.BAD_GATEWAY,
              response.statusCode() == 404
                  ? "SESSION_REST_NOT_FOUND"
                  : response.statusCode() >= 400
                          && response.statusCode() < 500
                          && response.statusCode() != 408
                      ? "SESSION_REST_REJECTED"
                      : "SESSION_REST_FAILED",
              "Flink 请求失败 (HTTP "
                  + response.statusCode()
                  + ")："
                  + text.substring(0, Math.min(text.length(), 12000)));
        return text;
      }
    } catch (BusinessException ex) {
      throw ex;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw unavailable("Flink 请求已中断");
    } catch (Exception ex) {
      throw unavailable("无法连接 Session Flink REST，请检查集群就绪状态及网络");
    }
  }

  /** 将通信错误转换为上游不可用错误，调用方负责过滤敏感信息。 */
  private BusinessException unavailable(String message) {
    return new BusinessException(HttpStatus.BAD_GATEWAY, "SESSION_REST_FAILED", message);
  }
}
