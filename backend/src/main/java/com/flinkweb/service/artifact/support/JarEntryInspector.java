package com.flinkweb.service.artifact.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.exception.common.BusinessException;
import java.io.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import org.springframework.asm.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 仅分析字节码中的公开静态 main 方法，绝不加载或执行用户类。 */
@Component
public class JarEntryInspector {
  private final ArtifactReferences references;
  private final ObjectMapper mapper;

  /** 注入统一文件读取及响应工具。 */
  public JarEntryInspector(ArtifactReferences references, ObjectMapper mapper) {
    this.references = references;
    this.mapper = mapper;
  }

  /** 有界读取归档并返回排序去重的入口类，支持 Flink Program-Class 清单。 */
  public ObjectNode inspect(String reference) {
    if (!references
        .describe(reference)
        .path("filename")
        .asText()
        .toLowerCase(Locale.ROOT)
        .endsWith(".jar")) fail("主程序请选择 JAR 文件");
    try (InputStream source = references.open(reference)) {
      return scan(source);
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      fail("无法识别 JAR 入口类，请检查文件格式、读取权限或手动填写入口类");
      return null;
    }
  }

  /** 限制压缩数据、解压总量、单类大小及项数，避免恶意归档耗尽资源。 */
  public ObjectNode scan(InputStream source) throws IOException {
    InputStream bounded =
        new FilterInputStream(source) {
          private long count;

          /** 计入单字节读取量。 */
          @Override
          public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++count > 256L * 1024 * 1024) fail("JAR 识别最多读取 256 MiB");
            return value;
          }

          /** 计入批量读取量。 */
          @Override
          public int read(byte[] bytes, int offset, int length) throws IOException {
            int n = in.read(bytes, offset, length);
            if (n > 0 && (count += n) > 256L * 1024 * 1024) fail("JAR 识别最多读取 256 MiB");
            return n;
          }
        };
    SortedSet<String> mains = new TreeSet<>();
    Set<String> classes = new HashSet<>();
    long total = 0;
    int count = 0;
    var header = new PushbackInputStream(bounded, 4);
    byte[] signature = header.readNBytes(4);
    if (signature.length != 4
        || signature[0] != 'P'
        || signature[1] != 'K'
        || !(signature[2] == 3 && signature[3] == 4 || signature[2] == 5 && signature[3] == 6))
      fail("文件不是有效的 JAR/ZIP 归档");
    header.unread(signature);
    try (ZipInputStream jar = new ZipInputStream(header)) {
      Manifest manifest = null;
      ZipEntry entry;
      while ((entry = jar.getNextEntry()) != null) {
        if (++count > 100000) fail("JAR 文件项数过多");
        if (entry.isDirectory()) continue;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        long size = 0;
        boolean classFile =
            entry.getName().endsWith(".class") && !entry.getName().startsWith("META-INF/versions/");
        boolean manifestFile = entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF");
        while ((n = jar.read(buffer)) != -1) {
          total += n;
          size += n;
          if (total > 512L * 1024 * 1024
              || classFile && size > 8L * 1024 * 1024
              || manifestFile && size > 1024 * 1024) fail("JAR 解压大小超过识别限制");
          if (classFile || manifestFile) bytes.write(buffer, 0, n);
        }
        if (manifestFile) manifest = new Manifest(new ByteArrayInputStream(bytes.toByteArray()));
        if (!classFile) continue;
        ClassReader reader = new ClassReader(bytes.toByteArray());
        String name = reader.getClassName().replace('/', '.');
        classes.add(name);
        reader.accept(
            new ClassVisitor(Opcodes.ASM9) {
              /** 只读取入口方法签名，不解析或执行方法体。 */
              @Override
              public MethodVisitor visitMethod(
                  int access,
                  String method,
                  String descriptor,
                  String signature,
                  String[] exceptions) {
                if ((access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC))
                        == (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)
                    && method.equals("main")
                    && descriptor.equals("([Ljava/lang/String;)V")) mains.add(name);
                return null;
              }
            },
            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
      }
      if (manifest != null) {
        String program = manifest.getMainAttributes().getValue("program-class");
        if (program != null && classes.contains(program)) mains.add(program);
      }
    }
    ObjectNode result = mapper.createObjectNode();
    mains.forEach(result.putArray("entryClasses")::add);
    return result;
  }

  /** 返回可手动纠正的识别错误。 */
  private void fail(String message) {
    throw new BusinessException(
        HttpStatus.BAD_REQUEST, "JAR_INSPECTION_FAILED", "entryClass: " + message);
  }
}
