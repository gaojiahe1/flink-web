package com.flinkweb;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.client.catalog.HiveMetastoreClient;
import com.flinkweb.exception.common.BusinessException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.apache.thrift.protocol.*;
import org.apache.thrift.transport.TIOStreamTransport;
import org.junit.jupiter.api.Test;

/** 通过真实 TCP / 官方 Thrift 编解码验证 Metastore 只读方法与跨版本字段投影。 */
class HiveMetastoreClientTest {
  final HiveMetastoreClient client = new HiveMetastoreClient(new ObjectMapper());

  /** 为每种官方响应提供字段编码器。 */
  @FunctionalInterface
  interface Reply {
    /** 编码成功结果字段。 */
    void write(TProtocol protocol) throws Exception;
  }

  /** 单次只读服务端替身检查方法及参数，结束后关闭所有套接字和线程。 */
  com.fasterxml.jackson.databind.node.ObjectNode query(
      String method,
      List<String> args,
      Reply reply,
      String database,
      String object,
      boolean functions)
      throws Exception {
    try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      server.setSoTimeout(5000);
      var worker = Executors.newSingleThreadExecutor();
      try {
        var done =
            worker.submit(
                () -> {
                  try (var socket = server.accept()) {
                    socket.setSoTimeout(5000);
                    var p =
                        new TBinaryProtocol(
                            new TIOStreamTransport(
                                socket.getInputStream(), socket.getOutputStream()));
                    var request = p.readMessageBegin();
                    assertThat(request.name).isEqualTo(method);
                    p.readStructBegin();
                    var actual = new ArrayList<String>();
                    while (true) {
                      var field = p.readFieldBegin();
                      if (field.type == TType.STOP) break;
                      assertThat(field.type).isEqualTo(TType.STRING);
                      actual.add(p.readString());
                      p.readFieldEnd();
                    }
                    p.readStructEnd();
                    p.readMessageEnd();
                    assertThat(actual).isEqualTo(args);
                    p.writeMessageBegin(new TMessage(method, TMessageType.REPLY, 1));
                    p.writeStructBegin(new TStruct("result"));
                    reply.write(p);
                    p.writeFieldStop();
                    p.writeStructEnd();
                    p.writeMessageEnd();
                    p.getTransport().flush();
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                });
        var result =
            client.metadata(
                List.of(URI.create("thrift://127.0.0.1:" + server.getLocalPort())),
                database,
                object,
                functions);
        done.get(5, TimeUnit.SECONDS);
        return result;
      } finally {
        worker.shutdownNow();
      }
    }
  }

  /** 写入官方定义的字符串字段。 */
  void string(TProtocol p, int field, String text) throws Exception {
    p.writeFieldBegin(new TField("", TType.STRING, (short) field));
    p.writeString(text);
    p.writeFieldEnd();
  }

  /** 写入 FieldSchema，类型参数与分区信息必须原样投影。 */
  void column(TProtocol p, String name, String type) throws Exception {
    p.writeStructBegin(new TStruct("FieldSchema"));
    string(p, 1, name);
    string(p, 2, type);
    string(p, 3, "字段注释");
    p.writeFieldStop();
    p.writeStructEnd();
  }

  /** 官方请求字段顺序、库表函数列表、复杂字段及分区的真实协议交互。 */
  @Test
  void listsAndProjectsReadOnlyMetadata() throws Exception {
    var databases =
        query(
            "get_all_databases",
            List.of(),
            p -> {
              p.writeFieldBegin(new TField("", TType.LIST, (short) 0));
              p.writeListBegin(new TList(TType.STRING, 2));
              p.writeString("z");
              p.writeString("warehouse");
              p.writeListEnd();
              p.writeFieldEnd();
            },
            "",
            "",
            false);
    assertThat(databases.path("databases").get(0).asText()).isEqualTo("warehouse");
    var tables =
        query(
            "get_all_tables",
            List.of("warehouse"),
            p -> {
              p.writeFieldBegin(new TField("", TType.LIST, (short) 0));
              p.writeListBegin(new TList(TType.STRING, 201));
              for (int i = 0; i < 201; i++) p.writeString("t" + i);
              p.writeListEnd();
              p.writeFieldEnd();
            },
            "warehouse",
            "",
            false);
    assertThat(tables.path("tables")).hasSize(200);
    assertThat(tables.path("truncated").asBoolean()).isTrue();
    var table =
        query(
            "get_table",
            List.of("warehouse", "events"),
            p -> {
              p.writeFieldBegin(new TField("", TType.STRUCT, (short) 0));
              p.writeStructBegin(new TStruct("Table"));
              string(p, 1, "events");
              string(p, 2, "warehouse");
              p.writeFieldBegin(new TField("", TType.STRUCT, (short) 7));
              p.writeStructBegin(new TStruct("StorageDescriptor"));
              p.writeFieldBegin(new TField("", TType.LIST, (short) 1));
              p.writeListBegin(new TList(TType.STRUCT, 1));
              column(p, "payload", "map<string,array<int>>");
              p.writeListEnd();
              p.writeFieldEnd();
              string(p, 2, "s3://private/location");
              p.writeFieldStop();
              p.writeStructEnd();
              p.writeFieldEnd();
              p.writeFieldBegin(new TField("", TType.LIST, (short) 8));
              p.writeListBegin(new TList(TType.STRUCT, 1));
              column(p, "dt", "string");
              p.writeListEnd();
              p.writeFieldEnd();
              string(p, 12, "EXTERNAL_TABLE");
              p.writeFieldStop();
              p.writeStructEnd();
              p.writeFieldEnd();
            },
            "warehouse",
            "events",
            false);
    assertThat(table.at("/columns/0/type").asText()).isEqualTo("MAP<STRING,ARRAY<INT>>");
    assertThat(table.at("/columns/1/partition").asBoolean()).isTrue();
    assertThat(table.toString()).doesNotContain("private/location");
    var functions =
        query(
            "get_functions",
            List.of("warehouse", ".*"),
            p -> {
              p.writeFieldBegin(new TField("", TType.LIST, (short) 0));
              p.writeListBegin(new TList(TType.STRING, 1));
              p.writeString("normalize");
              p.writeListEnd();
              p.writeFieldEnd();
            },
            "warehouse",
            "",
            true);
    assertThat(functions.at("/functions/0/name").asText()).isEqualTo("normalize");
    var function =
        query(
            "get_function",
            List.of("warehouse", "normalize"),
            p -> {
              p.writeFieldBegin(new TField("", TType.STRUCT, (short) 0));
              p.writeStructBegin(new TStruct("Function"));
              string(p, 1, "normalize");
              string(p, 2, "warehouse");
              string(p, 3, "example.Normalize");
              p.writeFieldStop();
              p.writeStructEnd();
              p.writeFieldEnd();
            },
            "warehouse",
            "normalize",
            true);
    assertThat(function.path("className").asText()).isEqualTo("example.Normalize");
  }

  /** 无效返回不会伪装成空目录或包含远端异常正文。 */
  @Test
  void rejectsWrongResultType() {
    assertThatThrownBy(
            () ->
                query(
                    "get_all_databases",
                    List.of(),
                    p -> string(p, 0, "sensitive remote failure"),
                    "",
                    "",
                    false))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("查询失败")
        .hasMessageNotContaining("sensitive");
  }
}
