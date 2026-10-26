package com.flinkweb.client.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.exception.common.BusinessException;
import java.io.*;
import java.net.*;
import java.time.Instant;
import java.util.*;
import org.apache.thrift.protocol.*;
import org.apache.thrift.transport.TIOStreamTransport;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 使用 Hive 官方只读 Thrift 方法浏览元数据，不加载 Hive/Hadoop 或用户插件。 */
@Component
public class HiveMetastoreClient {
  private final ObjectMapper mapper;

  /** 注入 JSON 工具，独立连接不保留远端状态。 */
  public HiveMetastoreClient(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /** 每次只查询一层元数据，限制回复字节、层级、集合大小及绝对截止时间。 */
  public ObjectNode metadata(List<URI> uris, String database, String object, boolean functions) {
    if ((!database.isBlank() && !database.matches("[A-Za-z_][A-Za-z0-9_-]{0,127}"))
        || (!object.isBlank()
            && (database.isBlank() || !object.matches("[A-Za-z_][A-Za-z0-9_$-]{0,127}"))))
      throw failure("Hive 数据库或对象名称无效");
    long deadline = nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
    for (URI uri : uris) {
      if (remainingMillis(deadline) <= 0) break;
      try (Socket socket = openSocket()) {
        var address = new InetSocketAddress(uri.getHost(), uri.getPort() == -1 ? 9083 : uri.getPort());
        long remaining = remainingMillis(deadline);
        if (remaining <= 0) break;
        socket.connect(address, (int) Math.min(2000, remaining));
        remaining = remainingMillis(deadline);
        if (remaining <= 0) break;
        socket.setSoTimeout((int) Math.min(3000, remaining));
        var input =
            new FilterInputStream(socket.getInputStream()) {
              int count;

              /** 每次读取前更新剩余预算，慢速响应也不能无限延长请求。 */
              private void before() throws IOException {
                long remaining = remainingMillis(deadline);
                if (remaining <= 0) throw new SocketTimeoutException("Hive metadata deadline");
                socket.setSoTimeout((int) Math.min(3000, Math.max(1, remaining)));
              }

              /** 限制单字节读取。 */
              @Override
              public int read() throws IOException {
                before();
                int n = in.read();
                if (n >= 0 && ++count > 2 * 1024 * 1024)
                  throw new IOException("Hive metadata budget");
                return n;
              }

              /** 限制批量读取。 */
              @Override
              public int read(byte[] b, int off, int len) throws IOException {
                before();
                int n = in.read(b, off, len);
                if (n > 0 && (count += n) > 2 * 1024 * 1024)
                  throw new IOException("Hive metadata budget");
                return n;
              }
            };
        var transport = new TIOStreamTransport(input, socket.getOutputStream());
        var protocol = new TBinaryProtocol(transport, 1024 * 1024, 10000, true, true);
        String method =
            database.isBlank()
                ? "get_all_databases"
                : object.isBlank()
                    ? functions ? "get_functions" : "get_all_tables"
                    : functions ? "get_function" : "get_table";
        List<String> args =
            database.isBlank()
                ? List.of()
                : object.isBlank()
                    ? functions ? List.of(database, ".*") : List.of(database)
                    : List.of(database, object);
        JsonNode result = call(protocol, method, args);
        return project(result, database, object, functions);
      } catch (Exception ignored) {
        if (nanoTime() >= deadline) break;
      }
    }
    throw failure("Hive Metastore 查询失败，请检查 URI、网络、服务协议及认证配置；平台未执行任何写入");
  }

  /** 使用单调时钟计算同一次查询的剩余预算，亚毫秒不足时不再启动阻塞操作。 */
  private long remainingMillis(long deadline) {
    return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline - nanoTime());
  }

  /** 创建每地址独立的套接字；查询结束或失败即关闭。 */
  Socket openSocket() { return new Socket(); }

  /** 提供同一次连接和读取共用的单调计时来源。 */
  long nanoTime() { return System.nanoTime(); }

  /** 按官方 field id 编码只读请求，拒绝异常或不同请求号的响应。 */
  private JsonNode call(TProtocol p, String method, List<String> args) throws Exception {
    p.writeMessageBegin(new TMessage(method, TMessageType.CALL, 1));
    p.writeStructBegin(new TStruct(method + "_args"));
    for (int i = 0; i < args.size(); i++) {
      p.writeFieldBegin(new TField("", TType.STRING, (short) (i + 1)));
      p.writeString(args.get(i));
      p.writeFieldEnd();
    }
    p.writeFieldStop();
    p.writeStructEnd();
    p.writeMessageEnd();
    p.getTransport().flush();
    TMessage response = p.readMessageBegin();
    if (response.type != TMessageType.REPLY || response.seqid != 1 || !method.equals(response.name))
      throw new IOException("Unexpected Hive reply");
    JsonNode value = read(p, TType.STRUCT, 0);
    p.readMessageEnd();
    if (!value.has("0")) throw new IOException("Hive metadata not available");
    return value.get("0");
  }

  /** 对未知版本新增字段有界读取，绝不把返回类型当成 Java 对象反序列化。 */
  private JsonNode read(TProtocol p, byte type, int depth) throws Exception {
    if (depth > 24) throw new IOException("Hive metadata nesting");
    return switch (type) {
      case TType.STRING -> TextNode.valueOf(p.readString());
      case TType.BOOL -> BooleanNode.valueOf(p.readBool());
      case TType.BYTE -> IntNode.valueOf(p.readByte());
      case TType.I16 -> IntNode.valueOf(p.readI16());
      case TType.I32 -> IntNode.valueOf(p.readI32());
      case TType.I64 -> LongNode.valueOf(p.readI64());
      case TType.DOUBLE -> DoubleNode.valueOf(p.readDouble());
      case TType.STRUCT -> {
        ObjectNode value = mapper.createObjectNode();
        p.readStructBegin();
        int fields = 0;
        while (true) {
          TField field = p.readFieldBegin();
          if (field.type == TType.STOP) break;
          if (++fields > 1000) throw new IOException("Hive metadata fields");
          value.set(Short.toString(field.id), read(p, field.type, depth + 1));
          p.readFieldEnd();
        }
        p.readStructEnd();
        yield value;
      }
      case TType.LIST, TType.SET -> {
        TList list = type == TType.LIST ? p.readListBegin() : null;
        TSet set = type == TType.SET ? p.readSetBegin() : null;
        int size = list == null ? set.size : list.size;
        byte element = list == null ? set.elemType : list.elemType;
        if (size < 0 || size > 10000) throw new IOException("Hive metadata list");
        ArrayNode value = mapper.createArrayNode();
        for (int i = 0; i < size; i++) value.add(read(p, element, depth + 1));
        if (list == null) p.readSetEnd();
        else p.readListEnd();
        yield value;
      }
      case TType.MAP -> {
        TMap map = p.readMapBegin();
        if (map.size < 0 || map.size > 10000) throw new IOException("Hive metadata map");
        for (int i = 0; i < map.size; i++) {
          read(p, map.keyType, depth + 1);
          read(p, map.valueType, depth + 1);
        }
        p.readMapEnd();
        yield mapper.createObjectNode();
      }
      default -> throw new IOException("Hive metadata type");
    };
  }

  /** 只交付库表、字段及函数描述，不返回表参数、凭据、位置或业务数据。 */
  private ObjectNode project(JsonNode value, String database, String object, boolean functions) {
    ObjectNode result =
        mapper
            .createObjectNode()
            .put("checkedAt", Instant.now().toString())
            .put("truncated", false);
    if (object.isBlank()) {
      if (!value.isArray()) throw failure("Hive 元数据响应格式无效");
      var items =
          result.putArray(database.isBlank() ? "databases" : functions ? "functions" : "tables");
      List<String> sorted = new ArrayList<>();
      for (var item : value) sorted.add(item.asText());
      Collections.sort(sorted);
      for (String name : sorted) {
        if (items.size() == 200) {
          result.put("truncated", true);
          break;
        }
        if (database.isBlank()) items.add(name);
        else items.addObject().put("name", name).put("type", functions ? "HIVE_FUNCTION" : "TABLE");
      }
    } else if (functions)
      result
          .put("name", value.path("1").asText())
          .put("database", value.path("2").asText())
          .put("className", value.path("3").asText())
          .put("kind", "HIVE_FUNCTION");
    else {
      var columns = result.putArray("columns");
      for (var col : value.path("7").path("1")) column(columns, col, false);
      for (var col : value.path("8")) column(columns, col, true);
      result
          .put("truncated", value.path("7").path("1").size() + value.path("8").size() > 100)
          .put("tableType", value.path("12").asText())
          .put("name", value.path("1").asText());
    }
    return result;
  }

  /** 字段包含分区标识及原始 Hive 类型，避免假定复杂类型已验证。 */
  private void column(ArrayNode columns, JsonNode field, boolean partition) {
    if (columns.size() >= 100) return;
    columns
        .addObject()
        .put("name", field.path("1").asText())
        .put("type", field.path("2").asText().toUpperCase(Locale.ROOT))
        .put("sourceType", field.path("2").asText())
        .put("comment", field.path("3").asText())
        .put("nullable", true)
        .put("partition", partition);
  }

  /** 错误不携带远端异常或敏感配置。 */
  private BusinessException failure(String message) {
    return new BusinessException(HttpStatus.BAD_GATEWAY, "HIVE_METADATA_FAILED", message);
  }
}
