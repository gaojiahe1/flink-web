package com.flinkweb.service.sql.support;

import com.flinkweb.client.storage.S3StorageClient;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 打包验证专属 JAR；合并 Connector 服务描述，拒绝冲突类和过大文件。 */
@Component
public class ValidationJarBundle {
  private final S3StorageClient storage;
  private final RecordRepository records;
  private final com.flinkweb.service.artifact.support.ArtifactReferences references;

  /** 注入受管 S3 下载与历史制品登记。 */
  public ValidationJarBundle(
      S3StorageClient storage,
      RecordRepository records,
      com.flinkweb.service.artifact.support.ArtifactReferences references) {
    this.storage = storage;
    this.records = records;
    this.references = references;
  }

  /** Runner 与连接器通过同一个用户代码 JAR 分发到 JM/TM，不改变正在运行的 Session 类路径。 */
  public Path build(List<String> references) throws IOException {
    Path target = Files.createTempFile("fw-sql-validation-", ".jar");
    try {
      Map<String, byte[]> digests = new HashMap<>();
      Map<String, LinkedHashSet<String>> services = new LinkedHashMap<>();
      long total = 0;
      try (var out = new JarOutputStream(Files.newOutputStream(target))) {
        for (String reference : references) {
          try (var stream = this.references.open(reference);
              var jar = new JarInputStream(stream)) {
            JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
              String name = entry.getName();
              if (entry.isDirectory()
                  || name.equalsIgnoreCase("META-INF/MANIFEST.MF")
                  || name.matches("(?i)META-INF/.*\\.(SF|RSA|DSA|EC)")
                  || name.endsWith("module-info.class")) continue;
              if (name.startsWith("/") || name.contains("..") || name.contains("\\"))
                fail("JAR 包含不安全路径");
              byte[] data = jar.readNBytes(16 * 1024 * 1024 + 1);
              total += data.length;
              if (data.length > 16 * 1024 * 1024 || total > 256L * 1024 * 1024)
                fail("SQL 验证依赖解压后总大小不得超过 256 MiB，单项不得超过 16 MiB");
              if (name.startsWith("META-INF/services/")) {
                var providers = services.computeIfAbsent(name, k -> new LinkedHashSet<>());
                new String(data, StandardCharsets.UTF_8)
                    .lines()
                    .map(s -> s.split("#", 2)[0].trim())
                    .filter(s -> !s.isBlank())
                    .forEach(providers::add);
                continue;
              }
              byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
              var previous = digests.putIfAbsent(name, digest);
              if (previous != null) {
                if (name.endsWith(".class") && !Arrays.equals(previous, digest))
                  fail("依赖存在不同版本的同名类：" + name + "，请整理 Connector 依赖");
                continue;
              }
              out.putNextEntry(new JarEntry(name));
              out.write(data);
              out.closeEntry();
            }
          }
        }
        if (!digests.containsKey("com/flinkweb/sql/SqlRunner.class"))
          fail("所选 Runner 不包含 com.flinkweb.sql.SqlRunner");
        for (var service : services.entrySet()) {
          out.putNextEntry(new JarEntry(service.getKey()));
          out.write(
              (String.join("\n", service.getValue()) + "\n").getBytes(StandardCharsets.UTF_8));
          out.closeEntry();
        }
      }
      return target;
    } catch (Exception ex) {
      Files.deleteIfExists(target);
      if (ex instanceof BusinessException business) throw business;
      throw new IOException("无法打包 SQL Runner 或依赖，请检查文件格式", ex);
    }
  }

  /** 依赖错误在提交之前返回，避免产生半运行作业。 */
  private void fail(String message) {
    throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_VALIDATION_DEPENDENCY", message);
  }
}
