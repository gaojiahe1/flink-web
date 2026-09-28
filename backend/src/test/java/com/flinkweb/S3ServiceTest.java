package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.client.storage.S3StorageClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;

/** 使用本地 S3 协议替身验证签名请求、目录解析及流式下载。 */
class S3ServiceTest {
  HttpServer server;
  S3StorageClient service;

  /** 启动本地 S3 协议替身并设置测试专用凭证。 */
  @BeforeEach
  void start() throws Exception {
    System.setProperty("aws.accessKeyId", "local-test");
    System.setProperty("aws.secretAccessKey", "local-test-only");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(
                exchange.getRequestURI().getPath().contains("missing") ? 404 : 200, -1);
            exchange.close();
            return;
          }
          String body =
              exchange.getRequestURI().getRawQuery() != null
                  ? """
<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
<Name>test-bucket</Name><Prefix>artifacts/</Prefix><KeyCount>1</KeyCount><MaxKeys>200</MaxKeys><IsTruncated>false</IsTruncated>
<Contents><Key>artifacts/job.jar</Key><LastModified>2026-01-01T00:00:00.000Z</LastModified><ETag>"abc"</ETag><Size>4</Size><StorageClass>STANDARD</StorageClass></Contents>
<CommonPrefixes><Prefix>artifacts/jobs/</Prefix></CommonPrefixes></ListBucketResult>
"""
                  : "data";
          byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/xml");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    server.start();
    service =
        new S3StorageClient(
            new ObjectMapper(),
            "test-bucket",
            "artifacts/",
            "http://127.0.0.1:" + server.getAddress().getPort(),
            "us-east-1",
            "s3://state-bucket/checkpoints");
  }

  /** 关闭协议替身及 S3 客户端，恢复测试系统属性。 */
  @AfterEach
  void stop() {
    service.close();
    server.stop(0);
    System.clearProperty("aws.accessKeyId");
    System.clearProperty("aws.secretAccessKey");
  }

  /** 验证目录分页解析、签名请求及对象流式下载。 */
  @Test
  void parsesS3DirectoryAndDownloadsThroughSignedHttp() throws Exception {
    var listing = service.list("", null);
    assertThat(listing.path("objects").get(0).path("key").asText()).isEqualTo("artifacts/job.jar");
    assertThat(listing.path("directories").get(0).asText()).isEqualTo("artifacts/jobs/");
    try (var stream = service.download("artifacts/job.jar")) {
      assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("data");
    }
    assertThatThrownBy(() -> service.list("../outside", null))
        .isInstanceOf(com.flinkweb.exception.common.BusinessException.class);
  }

  /** 验证上传路径按目录隔离，同名文件使用随机 ID，非法目录和主包扩展名被拒绝。 */
  @Test
  void uploadsToCategorizedPathsWithoutOverwriting() {
    var file =
        new org.springframework.mock.web.MockMultipartFile(
            "file", "job.jar", "application/java-archive", "data".getBytes(StandardCharsets.UTF_8));
    var first = service.upload(file, "main");
    var second = service.upload(file, "main");
    assertThat(first.path("uri").asText()).contains("/artifacts/main/").endsWith("/job.jar");
    assertThat(first.path("uri").asText()).isNotEqualTo(second.path("uri").asText());
    assertThat(first.path("sha256").asText()).hasSize(64);
    assertThat(service.upload(file, "dependency").path("uri").asText())
        .contains("/artifacts/dependency/");
    assertThatThrownBy(() -> service.upload(file, "../outside"))
        .isInstanceOf(com.flinkweb.exception.common.BusinessException.class);
    var text =
        new org.springframework.mock.web.MockMultipartFile(
            "file", "config.txt", "text/plain", "data".getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> service.upload(text, "main"))
        .isInstanceOf(com.flinkweb.exception.common.BusinessException.class);
  }

  /** 快照检查允许制品桶和配置的状态桶，拒绝跨桶和目录穿越，区分对象缺失。 */
  @Test
  void checksSnapshotMetadataInConfiguredStateBucket() {
    assertThat(service.checkSnapshot("s3://state-bucket/savepoints/one").path("exists").asBoolean())
        .isTrue();
    assertThat(
            service
                .checkSnapshot("s3://test-bucket/checkpoints/one/_metadata")
                .path("exists")
                .asBoolean())
        .isTrue();
    assertThat(service.checkSnapshot("s3://state-bucket/missing").path("exists").asBoolean())
        .isFalse();
    for (String path :
        java.util.List.of(
            "s3://other-bucket/x",
            "s3://state-bucket/../x",
            "s3://state-bucket/x?token=x",
            "s3://state-bucket:9000/x")) {
      assertThatThrownBy(() -> service.checkSnapshot(path))
          .isInstanceOf(com.flinkweb.exception.common.BusinessException.class);
    }
  }
}
