package com.flinkweb.client.kubernetes;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.config.platform.PlatformProperties;
import com.flinkweb.exception.common.BusinessException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import javax.net.ssl.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 封装目标 Namespace 的 Kubernetes REST 请求、ServiceAccount 认证和 TLS 校验；不持有作业缓存。 */
@Component
public class KubernetesClient {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(KubernetesClient.class);
  private final ObjectMapper mapper;
  private final HttpClient client;
  private final String endpoint, tokenFile, namespace;

  /** 读取 API 地址和凭据文件配置，构建具备集群 CA 校验的 HTTP 客户端。 */
  public KubernetesClient(
      ObjectMapper mapper,
      PlatformProperties properties,
      @Value("${platform.kubernetes.url:https://kubernetes.default.svc}") String endpoint,
      @Value(
              "${platform.kubernetes.token-file:/var/run/secrets/kubernetes.io/serviceaccount/token}")
          String tokenFile,
      @Value("${platform.kubernetes.ca-file:/var/run/secrets/kubernetes.io/serviceaccount/ca.crt}")
          String caFile) {
    this.mapper = mapper;
    this.endpoint = endpoint.replaceAll("/+$", "");
    this.tokenFile = tokenFile;
    this.namespace = properties.namespace();
    if (!namespace.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?"))
      throw new IllegalArgumentException("Invalid namespace");
    var builder =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER);
    if (Files.exists(Path.of(caFile))) {
      try (var input = Files.newInputStream(Path.of(caFile))) {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null);
        int i = 0;
        for (var cert : CertificateFactory.getInstance("X.509").generateCertificates(input))
          store.setCertificateEntry("ca-" + i++, cert);
        TrustManagerFactory tm =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(store);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, tm.getTrustManagers(), null);
        builder.sslContext(ssl);
      } catch (Exception ex) {
        throw new IllegalStateException("Invalid Kubernetes CA configuration");
      }
    }
    client = builder.build();
  }

  /** 返回当前 Namespace 的 FlinkDeployment API 路径。 */
  public String base() {
    return "/apis/flink.apache.org/v1beta1/namespaces/" + namespace + "/flinkdeployments";
  }

  /** 返回当前 Namespace 的 Kubernetes 核心资源 API 路径。 */
  public String core() {
    return "/api/v1/namespaces/" + namespace;
  }

  /** 发送带超时和认证的请求，区分资源不存在与写入失败并转换 API 错误。 */
  public JsonNode call(String method, String path, JsonNode body) {
    long started = System.nanoTime();
    try {
      requireToken();
      var request =
          HttpRequest.newBuilder(URI.create(endpoint + path))
              .timeout(Duration.ofSeconds(25))
              .header("Authorization", "Bearer " + Files.readString(Path.of(tokenFile)).trim())
              .header("Accept", "application/json");
      if (body != null) request.header("Content-Type", "application/json");
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(body.toString()));
      var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
      log.debug(
          "Kubernetes response method={} resource={} status={} elapsedMs={}",
          method,
          path.split("\\?", 2)[0],
          response.statusCode(),
          (System.nanoTime() - started) / 1_000_000);
      if (response.statusCode() == 404 && method.equals("GET")) return MissingNode.getInstance();
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        String code =
            response.statusCode() == 409
                ? "KUBERNETES_CONFLICT"
                : response.statusCode() == 403
                    ? "KUBERNETES_FORBIDDEN"
                    : response.statusCode() == 401
                        ? "KUBERNETES_UNAUTHENTICATED"
                        : "KUBERNETES_REJECTED";
        HttpStatus status =
            response.statusCode() == 409 ? HttpStatus.CONFLICT : HttpStatus.BAD_GATEWAY;
        // API 错误正文可能包含提交的密钥值，不直接返回给前端。
        var failure = new BusinessException(status, code, rejection(response.statusCode()));
        log.warn(
            "Kubernetes rejected method={} resource={} status={} elapsedMs={}",
            method,
            resource(path),
            response.statusCode(),
            (System.nanoTime() - started) / 1_000_000,
            failure);
        throw failure;
      }
      return mapper.readTree(response.body());
    } catch (BusinessException ex) {
      log.warn(
          "Kubernetes request failed method={} resource={} code={}",
          method,
          resource(path),
          ex.code(),
          ex);
      throw ex;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.warn("Kubernetes request interrupted method={} resource={}", method, resource(path), ex);
      throw unavailable(ex);
    } catch (Exception ex) {
      log.warn(
          "Kubernetes transport failed method={} resource={} errorType={}",
          method,
          resource(path),
          ex.getClass().getSimpleName(),
          ex);
      throw unavailable(ex);
    }
  }

  /** 读取受限长度的文本响应，仅供固定 Namespace 的日志诊断调用。 */
  public String text(String path, int maximumBytes) {
    if (!path.startsWith(core() + "/pods/"))
      throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_LOG_PATH", "日志路径无效");
    try {
      requireToken();
      var request =
          HttpRequest.newBuilder(URI.create(endpoint + path))
              .timeout(Duration.ofSeconds(15))
              .header("Authorization", "Bearer " + Files.readString(Path.of(tokenFile)).trim())
              .GET()
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (var input = response.body()) {
        if (response.statusCode() != 200) {
          var failure =
              new BusinessException(
                  HttpStatus.BAD_GATEWAY,
                  "LOG_UNAVAILABLE",
                  "日志暂不可用；" + rejection(response.statusCode()));
          log.warn(
              "Kubernetes log rejected method=GET resource={} status={}",
              resource(path),
              response.statusCode(),
              failure);
          throw failure;
        }
        return readTail(input, Math.max(1, Math.min(maximumBytes, 4 * 1024 * 1024)));
      }
    } catch (BusinessException ex) {
      log.warn(
          "Kubernetes log failed method=GET resource={} code={}", resource(path), ex.code(), ex);
      throw ex;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      log.warn("Kubernetes log interrupted method=GET resource={}", resource(path), ex);
      throw unavailable(ex);
    } catch (Exception ex) {
      log.warn(
          "Kubernetes log transport failed method=GET resource={} errorType={}",
          resource(path),
          ex.getClass().getSimpleName(),
          ex);
      throw unavailable(ex);
    }
  }

  /** 流式保留日志末尾，避免字节上限截掉最新输出；总传输量同时有界。 */
  private String readTail(java.io.InputStream input, int capacity) throws java.io.IOException {
    byte[] tail = new byte[capacity], buffer = new byte[8192];
    int position = 0, size = 0;
    long received = 0;
    for (int count; (count = input.read(buffer)) != -1; ) {
      received += count;
      if (received > 64L * 1024 * 1024)
        throw new BusinessException(
            HttpStatus.BAD_GATEWAY, "LOG_TOO_LARGE", "最近日志超过 64 MiB，请减少查询行数或检查日志轮转");
      for (int offset = 0; offset < count; ) {
        int length = Math.min(count - offset, capacity - position);
        System.arraycopy(buffer, offset, tail, position, length);
        position = (position + length) % capacity;
        size = Math.min(capacity, size + length);
        offset += length;
      }
    }
    byte[] ordered = new byte[size];
    int first = size == capacity ? position : 0, length = Math.min(size, capacity - first);
    System.arraycopy(tail, first, ordered, 0, length);
    System.arraycopy(tail, 0, ordered, length, size - length);
    int start = 0;
    // 截断位置可能落在 UTF-8 多字节字符中间，跳过不完整的开头。
    if (received > capacity) while (start < size && (ordered[start] & 0xc0) == 0x80) start++;
    return new String(ordered, start, size - start, java.nio.charset.StandardCharsets.UTF_8);
  }

  /** 凭证文件不可读时给出明确错误，不输出文件内容。 */
  private void requireToken() {
    if (!Files.isReadable(Path.of(tokenFile)))
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "KUBERNETES_UNAVAILABLE",
          "未配置可读的 Kubernetes ServiceAccount 凭证");
  }

  /** 日志只记录资源路径，不记录查询参数、请求正文或上游错误正文。 */
  private String resource(String path) {
    String value = path.split("\\?", 2)[0].replaceAll("[\\r\\n\\t]", "_");
    return value.substring(0, Math.min(value.length(), 512));
  }

  /** 区分认证、授权和其他 API 拒绝，便于定位具体资源的 RBAC。 */
  private String rejection(int status) {
    return switch (status) {
      case 401 -> "Kubernetes 认证失败 (HTTP 401)，请检查 ServiceAccount 令牌是否有效";
      case 403 -> "Kubernetes 权限不足 (HTTP 403)，请检查 ServiceAccount 对该资源及操作的 RBAC 授权";
      default -> "Kubernetes 拒绝请求 (HTTP " + status + ")，请检查资源状态、CRD、RBAC 及配置";
    };
  }

  /** 生成不泄露端点凭据的 Kubernetes 连接错误。 */
  private BusinessException unavailable(Throwable cause) {
    return new BusinessException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "KUBERNETES_UNAVAILABLE",
        "无法连接 Kubernetes API，请检查地址、网络与证书",
        cause);
  }

  /** 读取指定作业资源，资源不存在时返回明确的业务错误。 */
  public ObjectNode require(String name) {
    var node = call("GET", base() + "/" + name, null);
    if (node.isMissingNode())
      throw new BusinessException(HttpStatus.NOT_FOUND, "DEPLOYMENT_NOT_FOUND", "集群中不存在该作业");
    return (ObjectNode) node;
  }
}
