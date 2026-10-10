package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.client.storage.S3StorageClient;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.artifact.support.*;
import java.io.*;
import java.util.*;
import java.util.jar.*;
import org.junit.jupiter.api.Test;
import org.springframework.asm.*;

/** 验证入口静态识别、外部地址规则和归档大小约束。 */
class JarEntryInspectorTest {
  private final ObjectMapper mapper = new ObjectMapper();

  /** 生成只有方法签名的字节码，不依赖类加载器或用户代码执行。 */
  private byte[] clazz(String name, int access) {
    var writer = new ClassWriter(0);
    writer.visit(
        Opcodes.V17, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null, "java/lang/Object", null);
    writer.visitMethod(access, "main", "([Ljava/lang/String;)V", null, null).visitEnd();
    writer.visitEnd();
    return writer.toByteArray();
  }

  /** 构造包含指定类的测试 JAR。 */
  private InputStream jar(Map<String, byte[]> entries) throws Exception {
    var bytes = new ByteArrayOutputStream();
    try (var out = new JarOutputStream(bytes)) {
      for (var entry : entries.entrySet()) {
        out.putNextEntry(new JarEntry(entry.getKey()));
        out.write(entry.getValue());
        out.closeEntry();
      }
    }
    return new ByteArrayInputStream(bytes.toByteArray());
  }

  /** 仅公开静态 main 是候选，结果支持单个、多个和空列表。 */
  @Test
  void discoversOnlyPublicStaticMainMethods() throws Exception {
    var inspector = new JarEntryInspector(mock(ArtifactReferences.class), mapper);
    int main = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
    var found =
        inspector.scan(
            jar(
                Map.of(
                    "a/Main.class",
                    clazz("a.Main", main),
                    "b/Main.class",
                    clazz("b.Main", main),
                    "c/Instance.class",
                    clazz("c.Instance", Opcodes.ACC_PUBLIC))));
    assertThat(found.path("entryClasses")).hasSize(2);
    assertThat(found.path("entryClasses").get(0).asText()).isEqualTo("a.Main");
    assertThat(
            inspector.scan(jar(Map.of("a/Main.class", clazz("a.Main", main)))).path("entryClasses"))
        .hasSize(1);
    assertThat(
            inspector
                .scan(jar(Map.of("a/Instance.class", clazz("a.Instance", Opcodes.ACC_PUBLIC))))
                .path("entryClasses"))
        .isEmpty();
  }

  /** 避免单个类压缩炸弹耗尽分析进程内存。 */
  @Test
  void oversizedClassIsRejected() throws Exception {
    var inspector = new JarEntryInspector(mock(ArtifactReferences.class), mapper);
    try (var source = jar(Map.of("Huge.class", new byte[8 * 1024 * 1024 + 1]))) {
      assertThatThrownBy(() -> inspector.scan(source)).hasMessageContaining("解压大小");
    }
    assertThatThrownBy(
            () ->
                inspector.scan(
                    new ByteArrayInputStream(
                        "not a jar".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
        .hasMessageContaining("有效的 JAR");
    try (var source = jar(Map.of("META-INF/MANIFEST.MF", new byte[1024 * 1024 + 1]))) {
      assertThatThrownBy(() -> inspector.scan(source)).hasMessageContaining("解压大小");
    }
  }

  /** 手动地址裁剪文件名，HTTP 白名单和敏感 URL 规则与下载器一致。 */
  @Test
  void externalReferencesValidateSchemeHostAndFilename() {
    var references =
        new ArtifactReferences(
            mock(S3StorageClient.class),
            mock(RecordRepository.class),
            mapper,
            "files.test,files.test:8080");
    assertThat(
            references.describe("http://files.test:8080/path/main.jar").path("filename").asText())
        .isEqualTo("main.jar");
    assertThat(
            references
                .describe("s3://external-bucket/config/runtime.yaml")
                .path("filename")
                .asText())
        .isEqualTo("runtime.yaml");
    for (String value :
        List.of(
            "http://unlisted.test/main.jar",
            "https://files.test/main.jar?token=x",
            "https://user:pass@files.test/main.jar",
            "s3://bucket/../main.jar",
            "s3://bucket/folder/",
            "file:///main.jar"))
      assertThatThrownBy(() -> references.describe(value)).isInstanceOf(RuntimeException.class);
  }
}
