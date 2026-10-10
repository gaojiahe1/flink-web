package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.flinkweb.client.storage.S3StorageClient;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.sql.support.ValidationJarBundle;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

/** 验证动态依赖的服务发现合并、冲突检查及上传文件安全边界。 */
class ValidationJarBundleTest {
  /** 构造仅用于测试的受管 JAR 对象。 */
  private byte[] jar(Map<String, String> entries) throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var output = new JarOutputStream(bytes)) {
      for (var entry : entries.entrySet()) {
        output.putNextEntry(new JarEntry(entry.getKey()));
        output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
      }
    }
    return bytes.toByteArray();
  }

  /** 模拟 S3 流式下载的正文和响应头。 */
  private ResponseInputStream<GetObjectResponse> stream(Map<String, String> entries)
      throws Exception {
    return new ResponseInputStream<>(
        GetObjectResponse.builder().build(), new ByteArrayInputStream(jar(entries)));
  }

  /** Print、文件格式与 Connector 的服务发现列表合并去重，签名文件不污染新包。 */
  @Test
  void serviceProvidersAreMergedAndSignaturesRemoved() throws Exception {
    var storage = mock(S3StorageClient.class);
    when(storage.download("runner.jar"))
        .thenReturn(
            stream(
                Map.of(
                    "com/flinkweb/sql/SqlRunner.class", "runner",
                    "META-INF/services/org.apache.flink.table.factories.Factory", "PrintFactory\n",
                    "META-INF/OLD.SF", "signature")));
    when(storage.download("connector.jar"))
        .thenReturn(
            stream(
                Map.of(
                    "META-INF/services/org.apache.flink.table.factories.Factory",
                        "PrintFactory\nCsvFactory # comment\nFileFactory\n",
                    "Connector.class", "connector")));
    var bundle =
        new ValidationJarBundle(
            storage,
            mock(RecordRepository.class),
            new com.flinkweb.service.artifact.support.ArtifactReferences(
                storage,
                mock(RecordRepository.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                ""));
    Path path = bundle.build(List.of("s3:runner.jar", "s3:connector.jar"));
    try (var result = new JarFile(path.toFile())) {
      assertThat(result.getEntry("META-INF/OLD.SF")).isNull();
      String services =
          new String(
              result
                  .getInputStream(
                      result.getEntry("META-INF/services/org.apache.flink.table.factories.Factory"))
                  .readAllBytes(),
              StandardCharsets.UTF_8);
      assertThat(services.lines().toList())
          .containsExactly("PrintFactory", "CsvFactory", "FileFactory");
      assertThat(result.getEntry("Connector.class")).isNotNull();
    } finally {
      Files.delete(path);
    }
  }

  /** 不同版本的同名类和目录穿越在提交前被拒绝。 */
  @Test
  void conflictingClassesAndUnsafeEntriesAreRejected() throws Exception {
    var storage = mock(S3StorageClient.class);
    when(storage.download("runner.jar"))
        .thenAnswer(
            invocation ->
                stream(
                    Map.of(
                        "com/flinkweb/sql/SqlRunner.class", "runner", "Duplicated.class", "one")));
    when(storage.download("connector.jar")).thenReturn(stream(Map.of("Duplicated.class", "two")));
    var bundle =
        new ValidationJarBundle(
            storage,
            mock(RecordRepository.class),
            new com.flinkweb.service.artifact.support.ArtifactReferences(
                storage,
                mock(RecordRepository.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                ""));
    assertThatThrownBy(() -> bundle.build(List.of("s3:runner.jar", "s3:connector.jar")))
        .hasMessageContaining("同名类");
    when(storage.download("unsafe.jar")).thenReturn(stream(Map.of("../outside", "invalid")));
    assertThatThrownBy(() -> bundle.build(List.of("s3:runner.jar", "s3:unsafe.jar")))
        .hasMessageContaining("不安全路径");
  }

  /** 禁止将任意网络 URI 作为动态依赖源。 */
  @Test
  void unmanagedNetworkDependencyIsRejected() {
    var records = mock(RecordRepository.class);
    when(records.require("artifacts", "external"))
        .thenReturn(
            new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode()
                .put("uri", "https://external.test/connector.jar"));
    var bundle =
        new ValidationJarBundle(
            mock(S3StorageClient.class),
            records,
            new com.flinkweb.service.artifact.support.ArtifactReferences(
                mock(S3StorageClient.class),
                records,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                ""));
    assertThatThrownBy(() -> bundle.build(List.of("external"))).hasMessageContaining("下载白名单");
  }
}
