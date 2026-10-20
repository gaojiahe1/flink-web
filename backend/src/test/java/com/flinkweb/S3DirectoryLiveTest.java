package com.flinkweb;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.client.storage.S3StorageClient;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** 对隔离的本地 MinIO 验证真实上传、复制及删除，不触碰部署环境。 */
@EnabledIfEnvironmentVariable(named = "FLINK_TEST_MINIO", matches = "http://127\\.0\\.0\\.1:[0-9]+")
class S3DirectoryLiveTest {
  /** 全目录复制包含未被引用的文件，源目录删除不能影响副本和共享文件。 */
  @Test
  void uploadedDirectoryCopiesAndDeletesByJobId() throws Exception {
    System.setProperty("aws.accessKeyId", "local-test");
    System.setProperty("aws.secretAccessKey", "local-test-only");
    String bucket = "test-" + UUID.randomUUID();
    var storage =
        new S3StorageClient(
            new ObjectMapper(),
            bucket,
            "artifacts/",
            System.getenv("FLINK_TEST_MINIO"),
            "us-east-1",
            "s3://" + bucket + "/state");
    var client = (S3Client) ReflectionTestUtils.getField(storage, "client");
    try {
      client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
      String from = UUID.randomUUID().toString(), to = UUID.randomUUID().toString();
      var file =
          new MockMultipartFile(
              "file",
              "main.jar",
              "application/java-archive",
              "payload".getBytes(StandardCharsets.UTF_8));
      var uploaded = storage.upload(file, storage.jobPrefix(from));
      assertThat(uploaded.path("uri").asText()).endsWith("/jobs/" + from + "/main.jar");
      client.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(storage.jobPrefix(from) + "nested/未引用 配置.txt")
              .build(),
          software.amazon.awssdk.core.sync.RequestBody.fromBytes(new byte[] {1, 2, 3}));
      storage.upload(file, "artifacts/shared/");
      storage.copyJobFiles(from, to, true);
      try (var stream = storage.download(storage.jobPrefix(to) + "main.jar")) {
        assertThat(stream.readAllBytes()).isEqualTo("payload".getBytes(StandardCharsets.UTF_8));
      }
      try (var stream = storage.download(storage.jobPrefix(to) + "nested/未引用 配置.txt")) {
        assertThat(stream.readAllBytes()).containsExactly(1, 2, 3);
      }
      storage.mkdir(storage.jobPrefix(from));
      for (String key : java.util.List.of(storage.jobPrefix(from).replaceAll("/$", ""), "artifacts/" + from, "artifacts/" + from + "/nested/legacy.jar"))
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(), software.amazon.awssdk.core.sync.RequestBody.fromBytes(new byte[] {7}));
      storage.deleteJobStorage(from, true);
      assertThat(storage.list("artifacts/", null).toString()).doesNotContain(from);
      assertThat(storage.list("artifacts/jobs/", null).toString()).doesNotContain(from);
      assertThat(storage.list(storage.jobPrefix(from), null).path("objects")).isEmpty();
      assertThat(storage.list(storage.jobPrefix(to), null).path("objects")).hasSize(1);
      assertThat(storage.list("artifacts/shared/", null).path("objects")).hasSize(1);
      storage.deleteJobStorage(to, true);
      storage.delete("artifacts/shared/main.jar");
      storage.mkdir("outside/data/");
      storage.upload(file, "outside/data/");
      client.putObject(
          PutObjectRequest.builder().bucket(bucket).key("outside/data/nested/配置.txt").build(),
          software.amazon.awssdk.core.sync.RequestBody.fromBytes(new byte[] {4, 5}));
      storage.upload(file, "outside/data-other/");
      assertThat(storage.deleteDirectory("outside/data/").path("deletedObjects").asLong())
          .isEqualTo(3);
      assertThat(storage.list("outside/data/", null).path("objects")).isEmpty();
      assertThat(storage.describe("outside/data-other/main.jar").path("filename").asText())
          .isEqualTo("main.jar");
      assertThat(storage.deleteDirectory("outside/data/").path("deletedObjects").asLong()).isZero();
      storage.delete("outside/data-other/main.jar");
      client.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build());
    } finally {
      storage.close();
      System.clearProperty("aws.accessKeyId");
      System.clearProperty("aws.secretAccessKey");
    }
  }
}
