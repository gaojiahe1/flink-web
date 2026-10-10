package com.flinkweb.client.storage;

import com.flinkweb.exception.common.BusinessException;
import java.net.URI;
import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 将 S3 服务错误转换为可排查的安全日志和页面提示，不保留上游正文或凭据。 */
final class S3FailureDiagnostics {
  /** 禁止实例化无状态诊断工具。 */
  private S3FailureDiagnostics() {}

  /** 记录操作与服务错误字段，返回仅含安全字段和原调用位置的业务错误。 */
  static BusinessException failure(
      Logger log,
      Throwable cause,
      String operation,
      String endpoint,
      String bucket,
      String region) {
    S3Exception service = null;
    Throwable root = cause;
    for (int i = 0; root != null && i < 16; i++) {
      if (root instanceof S3Exception error) {
        service = error;
        break;
      }
      if (root.getCause() == null || root.getCause() == root) break;
      root = root.getCause();
    }
    int status = service == null ? 0 : service.statusCode();
    String code =
        service == null || service.awsErrorDetails() == null
            ? "Unknown"
            : safeField(service.awsErrorDetails().errorCode());
    String requestId = service == null ? "Unknown" : safeField(service.requestId());
    String errorType = cause == null ? "Unknown" : cause.getClass().getSimpleName();
    String rootType = root == null ? "Unknown" : root.getClass().getSimpleName();
    var safeCause = trace(cause);
    log.warn(
        "S3 operation failed operation={} endpoint={} bucket={} region={} httpStatus={} s3Code={}"
            + " requestId={} errorType={} rootType={}",
        operation,
        safeEndpoint(endpoint),
        safeField(bucket),
        safeField(region),
        status,
        code,
        requestId,
        errorType,
        rootType,
        safeCause);
    String details = status == 0 ? rootType : "HTTP " + status + "，" + code;
    String message =
        "S3 " + operation + " 请求失败（" + details + "）。" + hint(code, operation, rootType);
    if (!"Unknown".equals(requestId)) message += " 请求 ID：" + requestId;
    // 保留每层原始栈帧和原因链；SDK 上游正文由固定诊断字段替代，防止签名凭据进入日志。
    return new BusinessException(HttpStatus.BAD_GATEWAY, "S3_ERROR", message, safeCause);
  }

  /** 生成日志专用的异常副本，保留每层原始类型说明、完整栈帧和 suppressed 关系。 */
  static Throwable trace(Throwable original) {
    return trace(original, new java.util.IdentityHashMap<>());
  }

  /** 按异常身份复制整条诊断链，处理循环引用，不截断底层网络与 TLS 异常栈。 */
  private static Throwable trace(
      Throwable original, java.util.IdentityHashMap<Throwable, Throwable> copies) {
    if (original == null) return null;
    if (copies.containsKey(original)) return copies.get(original);
    String message;
    if (original instanceof S3Exception service) {
      message =
          "httpStatus="
              + service.statusCode()
              + " s3Code="
              + (service.awsErrorDetails() == null
                  ? "Unknown"
                  : safeField(service.awsErrorDetails().errorCode()))
              + " requestId="
              + safeField(service.requestId());
    } else if (original instanceof software.amazon.awssdk.core.exception.SdkException) {
      message = "S3 SDK failure; see operation fields and nested causes";
    } else {
      message =
          original.getMessage() == null
              ? ""
              : original
                  .getMessage()
                  .replaceAll("(?i)(https?://)[^/\\s@]+@", "$1[REDACTED]@")
                  .replaceAll("(?i)(https?://[^\\s?#]+)[?#][^\\s]*", "$1?[REDACTED]")
                  .replaceAll(
                      "(?i)((?:password|secret|token|authorization|access[-_]?key|credential|signature)[\\s\"']*[:=][\\s\"']*)[^\\s,;\"']+",
                      "$1[REDACTED]");
    }
    var copy = new IllegalStateException(original.getClass().getName() + ": " + message);
    copies.put(original, copy);
    copy.setStackTrace(original.getStackTrace());
    if (original.getCause() != null) copy.initCause(trace(original.getCause(), copies));
    for (Throwable suppressed : original.getSuppressed()) {
      if (suppressed != original) copy.addSuppressed(trace(suppressed, copies));
    }
    return copy;
  }

  /** 限制诊断元数据长度和字符范围，拒绝上游控制字符和任意正文注入。 */
  private static String safeField(String value) {
    return value != null && value.matches("[A-Za-z0-9._+/=-]{1,200}") ? value : "Unknown";
  }

  /** 仅展示端点协议、主机及端口，排除用户信息、路径、查询串和片段。 */
  private static String safeEndpoint(String value) {
    if (value == null || value.isBlank()) return "AWS-default";
    try {
      URI uri = URI.create(value);
      if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
          || uri.getHost() == null) return "Invalid-endpoint";
      return uri.getScheme()
          + "://"
          + uri.getHost()
          + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    } catch (IllegalArgumentException ex) {
      return "Invalid-endpoint";
    }
  }

  /** 按服务错误码和网络根因提供固定提示，不把上游错误说明返回页面。 */
  private static String hint(String code, String operation, String rootType) {
    return switch (code) {
      case "AccessDenied", "AllAccessDisabled" ->
          operation.equals("ListObjectsV2")
              ? "请检查该桶的 ListBucket 权限和前缀限制；文件页需要列举桶根目录。"
              : "请检查该操作的对象权限、桶策略及存储侧访问限制。";
      case "InvalidAccessKeyId", "InvalidSecurity" -> "请检查 Access Key 是否有效并属于当前存储端点。";
      case "SignatureDoesNotMatch" -> "请检查密钥是否配对、AWS_REGION 是否符合存储要求，以及网关是否改写请求。";
      case "AuthorizationHeaderMalformed",
              "IncorrectEndpoint",
              "PermanentRedirect",
              "IllegalLocationConstraintException" ->
          "请检查 S3 接口端点与 AWS_REGION 是否符合存储服务要求。";
      case "NoSuchBucket" -> "请检查桶名和端点；平台不会自动创建桶。";
      case "NoSuchKey", "NotFound" -> "请检查对象路径是否存在。";
      case "RequestTimeTooSkewed", "RequestExpired" -> "请检查运行节点的系统时间同步。";
      case "ExpiredToken", "InvalidToken", "TokenRefreshRequired" ->
          "请检查临时凭据及 AWS_SESSION_TOKEN 是否有效。";
      case "SlowDown", "ServiceUnavailable", "InternalError" -> "存储服务暂时不可用或限流，请稍后重试并核对存储侧日志。";
      default ->
          switch (rootType) {
            case "UnknownHostException", "UnresolvedAddressException" ->
                "请检查 Pod 内的 DNS 解析和 S3 域名。";
            case "SSLHandshakeException",
                    "SSLException",
                    "CertificateException",
                    "ValidatorException",
                    "SunCertPathBuilderException",
                    "CertPathValidatorException" ->
                "请检查 S3 证书及 Java 信任的 CA。";
            case "ConnectException",
                    "SocketTimeoutException",
                    "HttpTimeoutException",
                    "ApiCallTimeoutException",
                    "ApiCallAttemptTimeoutException" ->
                "请检查 Pod 到 S3 的网络、端口和超时。";
            default -> "请根据日志中的操作、状态码、错误码和请求 ID 核对存储侧日志。";
          };
    };
  }
}
