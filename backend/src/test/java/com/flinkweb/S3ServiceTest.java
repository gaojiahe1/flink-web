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

  /** 固定作业目录上传和直接选择无需登记，目录边界仍由后端执行。 */
  @Test
  void directObjectReferencesAllowBucketRootAndRejectTraversal() {
    var file =
        new org.springframework.mock.web.MockMultipartFile(
            "file", "job.jar", "application/java-archive", "data".getBytes(StandardCharsets.UTF_8));
    var uploaded = service.upload(file, "artifacts/jobs/one/");
    assertThat(uploaded.path("id").asText()).isEqualTo("s3:artifacts/jobs/one/job.jar");
    assertThat(uploaded.path("uri").asText())
        .isEqualTo("s3://test-bucket/artifacts/jobs/one/job.jar");
    var reference = service.describe("artifacts/jobs/one/job.jar");
    assertThat(reference.has("sha256")).isFalse();
    assertThat(service.describe("outside/job.jar").path("uri").asText())
        .isEqualTo("s3://test-bucket/outside/job.jar");
    assertThatThrownBy(() -> service.describe("../outside/job.jar"))
        .isInstanceOf(com.flinkweb.exception.common.BusinessException.class);
    assertThatThrownBy(() -> service.delete("artifacts/jobs/"))
        .isInstanceOf(com.flinkweb.exception.common.BusinessException.class);
    assertThat(service.defaults().toString()).doesNotContain("local-test-only");
    assertThat(service.list("", null).path("rootPrefix").asText()).isEmpty();
    assertThat(service.list("", null).path("prefix").asText()).isEmpty();
    assertThat(service.defaults().path("rootPrefix").asText()).isEqualTo("artifacts/");
  }

  /** 替换网络客户端，验证批量删除和复制协议的边界及分页行为。 */
  private software.amazon.awssdk.services.s3.S3Client mockStorage() {
    service.close();
    var client = mock(software.amazon.awssdk.services.s3.S3Client.class);
    org.springframework.test.util.ReflectionTestUtils.setField(service, "client", client);
    return client;
  }

  /** 真实 HTTP 协议请求包含与 XML 正文匹配的 Content-MD5，满足传统 S3 批量删除要求。 */
  @Test
  void compatibleBulkDeletionIncludesCorrectMd5AndSignatureRegion() throws Exception {
    var removed = new java.util.concurrent.atomic.AtomicBoolean();
    var headers = new java.util.concurrent.atomic.AtomicReference<com.sun.net.httpserver.Headers>();
    var payload = new java.util.concurrent.atomic.AtomicReference<byte[]>();
    server.createContext(
        "/test-bucket",
        exchange -> {
          String body;
          if (exchange.getRequestMethod().equals("POST")) {
            headers.set(exchange.getRequestHeaders());
            payload.set(exchange.getRequestBody().readAllBytes());
            removed.set(true);
            body = "<DeleteResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"/>";
          } else {
            body =
                "<ListBucketResult"
                    + " xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><IsTruncated>false</IsTruncated>"
                    + (removed.get()
                        ? ""
                        : "<Contents><Key>external/data/file.txt</Key><Size>1</Size></Contents>")
                    + "</ListBucketResult>";
          }
          var bytes = body.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    assertThat(service.deleteDirectory("external/data/").path("deletedObjects").asLong())
        .isEqualTo(1);
    assertThat(headers.get().getFirst("Content-MD5"))
        .isEqualTo(
            java.util.Base64.getEncoder()
                .encodeToString(
                    java.security.MessageDigest.getInstance("MD5").digest(payload.get())));
    assertThat(headers.get().getFirst("Authorization"))
        .startsWith("AWS4-HMAC-SHA256")
        .contains("/us-east-1/s3/aws4_request");
  }

  /** 目录删除包含目录标记、嵌套文件和多批次，严格保留同名前缀相邻目录及根文件。 */
  @Test
  void deletesDirectoryRecursivelyWithoutTouchingAdjacentKeys() {
    var client = mockStorage();
    var keys = new java.util.LinkedHashSet<String>();
    keys.add("outside/data/");
    for (int i = 0; i < 1001; i++) keys.add("outside/data/nested/file-" + i);
    keys.add("outside/data-other/keep.jar");
    keys.add("outside/data");
    when(client.listObjectsV2(
            any(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class)))
        .thenAnswer(
            inv -> {
              var request =
                  (software.amazon.awssdk.services.s3.model.ListObjectsV2Request)
                      inv.getArgument(0);
              assertThat(request.bucket()).isEqualTo("test-bucket");
              assertThat(request.prefix()).isEqualTo("outside/data/");
              return software.amazon.awssdk.services.s3.model.ListObjectsV2Response.builder()
                  .contents(
                      keys.stream()
                          .filter(k -> k.startsWith(request.prefix()))
                          .limit(1000)
                          .map(
                              k ->
                                  software.amazon.awssdk.services.s3.model.S3Object.builder()
                                      .key(k)
                                      .build())
                          .toList())
                  .build();
            });
    when(client.deleteObjects(
            any(software.amazon.awssdk.services.s3.model.DeleteObjectsRequest.class)))
        .thenAnswer(
            inv -> {
              var request =
                  (software.amazon.awssdk.services.s3.model.DeleteObjectsRequest)
                      inv.getArgument(0);
              request.delete().objects().forEach(k -> keys.remove(k.key()));
              return software.amazon.awssdk.services.s3.model.DeleteObjectsResponse.builder()
                  .build();
            });
    assertThat(service.deleteDirectory("outside/data/").path("deletedObjects").asLong())
        .isEqualTo(1002);
    assertThat(keys).containsExactly("outside/data-other/keep.jar", "outside/data");
    assertThat(service.deleteDirectory("outside/data/").path("deletedObjects").asLong()).isZero();
    verify(client, times(2))
        .deleteObjects(any(software.amazon.awssdk.services.s3.model.DeleteObjectsRequest.class));
  }

  /** 目录删除拒绝根目录、目录穿越和没有分隔符的前缀，不发送任何存储请求。 */
  @Test
  void directoryDeletionRejectsUnsafePrefixesBeforeStorageCalls() {
    var client = mockStorage();
    for (String path :
        java.util.List.of(
            "",
            "/",
            "outside/data",
            "../data/",
            "outside//data/",
            "outside/../data/",
            "outside\\data/",
            "outside/\n/"))
      assertThatThrownBy(() -> service.deleteDirectory(path))
          .isInstanceOf(com.flinkweb.exception.common.BusinessException.class);
    verifyNoInteractions(client);
  }

  /** 部分删除失败保留根因和 WARN 堆栈，明确记录权限错误；不接受越界返回的对象。 */
  @Test
  void directoryFailureLogsThrowableAndPreservesCause() {
    var client = mockStorage();
    when(client.listObjectsV2(
            any(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class)))
        .thenReturn(
            software.amazon.awssdk.services.s3.model.ListObjectsV2Response.builder()
                .contents(
                    software.amazon.awssdk.services.s3.model.S3Object.builder()
                        .key("outside/data/a.jar")
                        .build())
                .build());
    when(client.deleteObjects(
            any(software.amazon.awssdk.services.s3.model.DeleteObjectsRequest.class)))
        .thenReturn(
            software.amazon.awssdk.services.s3.model.DeleteObjectsResponse.builder()
                .errors(
                    software.amazon.awssdk.services.s3.model.S3Error.builder()
                        .code("AccessDenied")
                        .build())
                .build());
    var logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(S3StorageClient.class);
    var appender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    logger.addAppender(appender);
    try {
      assertThatThrownBy(() -> service.deleteDirectory("outside/data/"))
          .isInstanceOf(com.flinkweb.exception.common.BusinessException.class)
          .hasCauseInstanceOf(IllegalStateException.class)
          .rootCause()
          .hasMessageContaining("AccessDenied");
      var failure =
          appender.list.stream()
              .filter(event -> event.getFormattedMessage().startsWith("S3 operation failed"))
              .findFirst()
              .orElseThrow();
      assertThat(failure.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
      assertThat(failure.getFormattedMessage())
          .contains("operation=DeleteObjects", "httpStatus=200", "s3Code=AccessDenied");
      assertThat(failure.getThrowableProxy().getMessage()).contains("AccessDenied");
      assertThat(
              ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(failure.getThrowableProxy()))
          .contains("S3StorageClient.deletePrefix", "S3StorageClient.deleteDirectory");
      when(client.listObjectsV2(
              any(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class)))
          .thenReturn(
              software.amazon.awssdk.services.s3.model.ListObjectsV2Response.builder()
                  .contents(
                      software.amazon.awssdk.services.s3.model.S3Object.builder()
                          .key("outside/data-other/a.jar")
                          .build())
                  .build());
      assertThatThrownBy(() -> service.deleteDirectory("outside/data/"))
          .rootCause()
          .hasMessageContaining("outside requested prefix");
      verify(client, times(1))
          .deleteObjects(any(software.amazon.awssdk.services.s3.model.DeleteObjectsRequest.class));
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  /** 删除仅扫描作业专属前缀，处理多批次并保留共享目录及相邻 UUID。 */
  @Test
  void deletesAllBatchesWithinExactJobPrefixes() {
    var client = mockStorage();
    String id = java.util.UUID.randomUUID().toString(), prefix = service.jobPrefix(id);
    var objects = new java.util.LinkedHashSet<String>();
    for (int i = 0; i < 1001; i++) objects.add("test-bucket/" + prefix + "file-" + i);
    objects.add("test-bucket/artifacts/shared/shared.jar");
    objects.add("test-bucket/" + prefix.substring(0, prefix.length() - 1) + "-other/file");
    objects.add("state-bucket/checkpoints/" + id + "/ha/data");
    when(client.listObjectsV2(
            any(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class)))
        .thenAnswer(
            inv -> {
              var request =
                  (software.amazon.awssdk.services.s3.model.ListObjectsV2Request)
                      inv.getArgument(0);
              var found =
                  objects.stream()
                      .filter(k -> k.startsWith(request.bucket() + "/" + request.prefix()))
                      .limit(1000)
                      .map(
                          k ->
                              software.amazon.awssdk.services.s3.model.S3Object.builder()
                                  .key(k.substring(request.bucket().length() + 1))
                                  .build())
                      .toList();
              return software.amazon.awssdk.services.s3.model.ListObjectsV2Response.builder()
                  .contents(found)
                  .build();
            });
    when(client.deleteObjects(
            any(software.amazon.awssdk.services.s3.model.DeleteObjectsRequest.class)))
        .thenAnswer(
            inv -> {
              var request =
                  (software.amazon.awssdk.services.s3.model.DeleteObjectsRequest)
                      inv.getArgument(0);
              for (var item : request.delete().objects())
                objects.remove(request.bucket() + "/" + item.key());
              return software.amazon.awssdk.services.s3.model.DeleteObjectsResponse.builder()
                  .build();
            });
    service.deleteJobStorage(id, true);
    assertThat(objects).hasSize(2).contains("test-bucket/artifacts/shared/shared.jar");
    verify(client, times(3))
        .deleteObjects(any(software.amazon.awssdk.services.s3.model.DeleteObjectsRequest.class));
    service.deleteJobStorage(id, true);
    verify(client, times(3))
        .deleteObjects(any(software.amazon.awssdk.services.s3.model.DeleteObjectsRequest.class));
    assertThatThrownBy(() -> service.deleteJobStorage("../shared", true))
        .isInstanceOf(com.flinkweb.exception.common.BusinessException.class);
  }

  /** 批量删除 HTTP 成功但含单项失败时必须报错，不误报完成。 */
  @Test
  void partialDeleteErrorsAreNotReportedAsSuccess() {
    var client = mockStorage();
    String id = java.util.UUID.randomUUID().toString();
    when(client.listObjectsV2(
            any(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class)))
        .thenReturn(
            software.amazon.awssdk.services.s3.model.ListObjectsV2Response.builder()
                .contents(
                    software.amazon.awssdk.services.s3.model.S3Object.builder()
                        .key(service.jobPrefix(id) + "main.jar")
                        .build())
                .build());
    when(client.deleteObjects(
            any(software.amazon.awssdk.services.s3.model.DeleteObjectsRequest.class)))
        .thenReturn(
            software.amazon.awssdk.services.s3.model.DeleteObjectsResponse.builder()
                .errors(
                    software.amazon.awssdk.services.s3.model.S3Error.builder()
                        .code("AccessDenied")
                        .build())
                .build());
    assertThatThrownBy(() -> service.deleteJobStorage(id, true))
        .isInstanceOf(com.flinkweb.exception.common.BusinessException.class);
  }

  /** 服务端复制作业依赖支持分页，包含源 ETag 校验，目标只使用新 UUID。 */
  @Test
  void copiesPaginatedFilesToIndependentDirectory() {
    var client = mockStorage();
    String from = java.util.UUID.randomUUID().toString(),
        to = java.util.UUID.randomUUID().toString();
    var first =
        software.amazon.awssdk.services.s3.model.S3Object.builder()
            .key(service.jobPrefix(from) + "one.jar")
            .eTag("etag-one")
            .build();
    var second =
        software.amazon.awssdk.services.s3.model.S3Object.builder()
            .key(service.jobPrefix(from) + "nested/two.jar")
            .eTag("etag-two")
            .build();
    when(client.listObjectsV2(
            any(software.amazon.awssdk.services.s3.model.ListObjectsV2Request.class)))
        .thenReturn(
            software.amazon.awssdk.services.s3.model.ListObjectsV2Response.builder()
                .contents(first)
                .isTruncated(true)
                .nextContinuationToken("next")
                .build(),
            software.amazon.awssdk.services.s3.model.ListObjectsV2Response.builder()
                .contents(second)
                .isTruncated(false)
                .build());
    service.copyJobFiles(from, to, true);
    var captured =
        org.mockito.ArgumentCaptor.forClass(
            software.amazon.awssdk.services.s3.model.CopyObjectRequest.class);
    verify(client, times(2)).copyObject(captured.capture());
    assertThat(captured.getAllValues())
        .allSatisfy(
            r -> {
              assertThat(r.destinationKey()).startsWith(service.jobPrefix(to));
              assertThat(r.copySource()).contains(from);
              assertThat(r.copySourceIfMatch()).startsWith("etag-");
            });
  }
}
