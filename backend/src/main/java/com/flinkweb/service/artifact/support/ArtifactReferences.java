package com.flinkweb.service.artifact.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.client.storage.S3StorageClient;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import java.io.*;
import java.net.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 统一解析平台对象和用户确认的外部文件地址，供挂载与静态检查复用。 */
@Component
public class ArtifactReferences {
  private final S3StorageClient storage;
  private final RecordRepository records;
  private final ObjectMapper mapper;
  private final Set<String> hosts;

  /** 注入存储、历史登记及 HTTP 下载白名单。 */
  public ArtifactReferences(
      S3StorageClient storage,
      RecordRepository records,
      ObjectMapper mapper,
      @Value("${platform.artifacts.allowed-hosts:}") String hosts) {
    this.storage = storage;
    this.records = records;
    this.mapper = mapper;
    this.hosts = new HashSet<>();
    for (String host : hosts.split(","))
      if (!host.isBlank()) this.hosts.add(host.trim().toLowerCase(Locale.ROOT));
  }

  /** 地址须指向具体文件，禁止凭据、签名参数、片段及不安全文件名。 */
  public ObjectNode describe(String reference) {
    if (reference.startsWith("s3:") && !reference.startsWith("s3://"))
      return storage.describe(reference.substring(3));
    if (!reference.matches("(?i)^(https?|s3)://.*")) {
      if (reference.isBlank() || reference.contains(":"))
        throw invalid("只支持 HTTP/HTTPS/S3 文件地址或已登记文件");
      return records.require("artifacts", reference);
    }
    URI uri;
    try {
      uri = URI.create(reference);
    } catch (IllegalArgumentException ex) {
      throw invalid("文件地址格式无效");
    }
    if (!Set.of("http", "https", "s3").contains(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || uri.getPath() == null
        || uri.getPath().contains("..")
        || uri.getPath().contains("\\")
        || "s3".equals(uri.getScheme()) && uri.getPort() != -1)
      throw invalid("请填写不含凭据和查询参数的 HTTP/HTTPS/S3 文件地址");
    String filename = uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
    if (!filename.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}"))
      throw invalid("地址最后一层须为文件名，仅支持字母、数字、点、下划线和连字符");
    if (!"s3".equals(uri.getScheme())
        && !hosts.contains(uri.getRawAuthority().toLowerCase(Locale.ROOT)))
      throw invalid("HTTP 文件主机未加入下载白名单，请联系管理员配置 FLINK_WEB_ARTIFACT_HOSTS");
    return mapper
        .createObjectNode()
        .put("id", reference)
        .put("filename", filename)
        .put("uri", reference);
  }

  /** 获取静态分析所需输入流；不执行 JAR，HTTP 不跟随重定向或传递平台凭据。 */
  public InputStream open(String reference) throws IOException {
    if (reference.startsWith("s3:") && !reference.startsWith("s3://"))
      return storage.download(reference.substring(3));
    ObjectNode artifact = describe(reference);
    URI uri = URI.create(artifact.path("uri").asText());
    if ("s3".equals(uri.getScheme()))
      return storage.download(uri.getHost(), uri.getPath().substring(1));
    if (!hosts.contains(uri.getRawAuthority().toLowerCase(Locale.ROOT)))
      throw invalid("HTTP 文件主机未加入下载白名单");
    for (InetAddress address : InetAddress.getAllByName(uri.getHost()))
      if (address.isLoopbackAddress()
          || address.isLinkLocalAddress()
          || address.isMulticastAddress()
          || address.isAnyLocalAddress()) throw invalid("HTTP 文件地址不可使用本机或链路地址");
    if (!artifact.path("credentialRef").asText().isBlank())
      throw invalid("静态识别不支持带鉴权的历史 HTTP 制品，请先上传至 S3");
    var connection = (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
    connection.setInstanceFollowRedirects(false);
    connection.setConnectTimeout(10000);
    connection.setReadTimeout(30000);
    try {
      if (connection.getResponseCode() != 200)
        throw invalid("HTTP 文件读取失败，状态码：" + connection.getResponseCode());
      InputStream body = connection.getInputStream();
      return new FilterInputStream(body) {
        /** 关闭正文并立即释放 HTTP 连接。 */
        @Override
        public void close() throws IOException {
          try {
            super.close();
          } finally {
            connection.disconnect();
          }
        }
      };
    } catch (IOException | RuntimeException ex) {
      connection.disconnect();
      throw ex;
    }
  }

  /** 使用固定业务错误，避免上游异常携带凭据。 */
  private BusinessException invalid(String message) {
    return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ARTIFACT", message);
  }
}
