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
      if (!Files.isReadable(Path.of(tokenFile)))
        throw new BusinessException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "KUBERNETES_UNAVAILABLE",
            "未配置 Kubernetes ServiceAccount 凭证");
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
        String code = response.statusCode() == 409 ? "KUBERNETES_CONFLICT" : "KUBERNETES_REJECTED";
        HttpStatus status =
            response.statusCode() == 409 ? HttpStatus.CONFLICT : HttpStatus.BAD_GATEWAY;
        // API 错误正文可能包含提交的密钥值，不直接返回给前端。
        throw new BusinessException(
            status, code, "Kubernetes 拒绝请求 (HTTP " + response.statusCode() + ")，请检查 CRD、RBAC 及配置");
      }
      return mapper.readTree(response.body());
    } catch (BusinessException ex) {
      log.warn("Kubernetes request failed method={} code={}", method, ex.code());
      throw ex;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (Exception ex) {
      log.warn(
          "Kubernetes transport failed method={} errorType={}",
          method,
          ex.getClass().getSimpleName());
      throw unavailable();
    }
  }

  /** 读取受限长度的文本响应，仅供固定 Namespace 的日志诊断调用。 */
  public String text(String path, int maximumBytes) {
    if (!path.startsWith(core() + "/pods/"))
      throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_LOG_PATH", "日志路径无效");
    try {
      var request =
          HttpRequest.newBuilder(URI.create(endpoint + path))
              .timeout(Duration.ofSeconds(15))
              .header("Authorization", "Bearer " + Files.readString(Path.of(tokenFile)).trim())
              .GET()
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (var input = response.body()) {
        if (response.statusCode() != 200)
          throw new BusinessException(
              HttpStatus.BAD_GATEWAY,
              "LOG_UNAVAILABLE",
              "日志暂不可用，请检查容器状态及 RBAC (HTTP " + response.statusCode() + ")");
        return new String(
            input.readNBytes(Math.min(maximumBytes, 262144)),
            java.nio.charset.StandardCharsets.UTF_8);
      }
    } catch (BusinessException ex) {
      throw ex;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw unavailable();
    } catch (Exception ex) {
      throw unavailable();
    }
  }

  /** 生成不泄露端点凭据的 Kubernetes 连接错误。 */
  private BusinessException unavailable() {
    return new BusinessException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "KUBERNETES_UNAVAILABLE",
        "无法连接 Kubernetes API，请检查地址、网络与证书");
  }

  /** 读取指定作业资源，资源不存在时返回明确的业务错误。 */
  public ObjectNode require(String name) {
    var node = call("GET", base() + "/" + name, null);
    if (node.isMissingNode())
      throw new BusinessException(HttpStatus.NOT_FOUND, "DEPLOYMENT_NOT_FOUND", "集群中不存在该作业");
    return (ObjectNode) node;
  }
}
