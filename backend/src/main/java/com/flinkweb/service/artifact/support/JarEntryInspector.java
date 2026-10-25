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

/** 静态分析 main 入口和 UDF 继承、签名与调用，绝不加载或执行用户类。 */
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
    Map<String, String> parents = new HashMap<>();
    Map<String, ObjectNode> functions = new TreeMap<>();
    Map<String, ObjectNode> details = new HashMap<>();
    long total = 0;
    long metadataSize = 0;
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
        if (name.length() > 500) fail("类名称过长，无法安全分析");
        classes.add(name);
        parents.put(
            name, reader.getSuperName() == null ? "" : reader.getSuperName().replace('/', '.'));
        ObjectNode function = mapper.createObjectNode().put("className", name);
        var signatures = function.putArray("signatures");
        var calls = function.putArray("methodCalls");
        boolean[] constructor = {false};
        reader.accept(
            new ClassVisitor(Opcodes.ASM9) {
              /** 读取入口签名及 UDF 方法调用线索，不执行方法体。 */
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
                if ((access & Opcodes.ACC_PUBLIC) != 0
                    && method.equals("<init>")
                    && descriptor.equals("()V")) constructor[0] = true;
                if ((access & Opcodes.ACC_PUBLIC) != 0
                    && Set.of(
                            "eval",
                            "accumulate",
                            "retract",
                            "merge",
                            "getValue",
                            "createAccumulator")
                        .contains(method)) {
                  if (signatures.size() < 32)
                    signatures.add(
                        method
                            + Arrays.toString(
                                Arrays.stream(Type.getArgumentTypes(descriptor))
                                    .map(Type::getClassName)
                                    .toArray())
                            + " → "
                            + Type.getReturnType(descriptor).getClassName());
                  return new MethodVisitor(Opcodes.ASM9) {
                    /** 只记录方法调用证据，不解释或执行字节码。 */
                    @Override
                    public void visitMethodInsn(
                        int opcode, String owner, String called, String desc, boolean itf) {
                      String value = owner.replace('/', '.') + "." + called;
                      if (calls.size() < 32 && !calls.toString().contains('"' + value + '"'))
                        calls.add(value);
                    }
                  };
                }
                return null;
              }
            },
            ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        metadataSize += function.toString().length();
        if (metadataSize > 2 * 1024 * 1024) fail("类元信息超过分析预算，请使用精简 UDF JAR");
        if (!signatures.isEmpty()) details.put(name, function);
        if ((reader.getAccess()
                    & (Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE))
                == Opcodes.ACC_PUBLIC
            && constructor[0]) {
          if (functions.size() >= 5000 || function.toString().length() > 24000)
            fail("可实例化类或函数元信息过多，请使用精简 UDF JAR");
          functions.put(name, function);
        }
      }
      if (manifest != null) {
        String program = manifest.getMainAttributes().getValue("program-class");
        if (program != null && classes.contains(program)) mains.add(program);
      }
    }
    ObjectNode result = mapper.createObjectNode();
    mains.forEach(result.putArray("entryClasses")::add);
    var udf = result.putArray("functions");
    Map<String, String> kinds =
        Map.of(
            "ScalarFunction",
            "SCALAR",
            "TableFunction",
            "TABLE",
            "AggregateFunction",
            "AGGREGATE",
            "TableAggregateFunction",
            "TABLE_AGGREGATE");
    for (var candidate : functions.entrySet()) {
      Set<String> visited = new HashSet<>();
      String parent = parents.get(candidate.getKey());
      while (parent != null && visited.add(parent)) {
        var inherited = details.get(parent);
        if (inherited != null)
          for (String field : List.of("signatures", "methodCalls")) {
            var target = candidate.getValue().withArray("/" + field);
            for (var item : inherited.path(field)) {
              boolean exists = false;
              for (var old : target) if (old.equals(item)) exists = true;
              if (!exists && target.size() < 32) target.add(item);
            }
          }
        if (parent.startsWith("org.apache.flink.table.functions.")
            && kinds.containsKey(parent.substring(parent.lastIndexOf('.') + 1))) {
          udf.add(
              candidate
                  .getValue()
                  .put("kind", kinds.get(parent.substring(parent.lastIndexOf('.') + 1))));
          break;
        }
        parent = parents.get(parent);
      }
    }
    result.put("note", "仅静态分析继承、签名和调用信息，不加载或执行用户类；缺少父类依赖时可能无法识别，运行兼容性需 SQL 验证。");
    return result;
  }

  /** 返回可手动纠正的识别错误。 */
  private void fail(String message) {
    throw new BusinessException(
        HttpStatus.BAD_REQUEST, "JAR_INSPECTION_FAILED", "entryClass: " + message);
  }
}
