package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.client.ai.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;

/** 真正 HTTP 分片与 SSE 协议回归，不连接外部模型。 */
class AiHarnessModelClientTest {
  final ObjectMapper mapper=new ObjectMapper();
  HttpServer server;
  final AtomicReference<String> response=new AtomicReference<>();
  final AtomicReference<String> request=new AtomicReference<>();
  AiHarnessModelClient client;
  ObjectNode model;
  /** 本机测试服务器逐字节发送，覆盖中文 UTF-8 及分片工具参数。 */
  @BeforeEach
  void setup()throws Exception{
    server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/v1/chat/completions",exchange->{request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);try(var out=exchange.getResponseBody()){for(byte value:response.get().getBytes(StandardCharsets.UTF_8)){out.write(value);out.flush();}}});server.start();
    client=new AiHarnessModelClient(mapper,new AiModelClient(mapper));model=mapper.createObjectNode().put("baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/v1").put("privateNetwork",true).put("model","test").put("timeoutSeconds",5);
  }
  /** 不遗留监听端口或服务线程。 */
  @AfterEach
  void close(){server.stop(0);}
  /** 真实增量保留中文，只有完整终止后才返回工具参数。 */
  @Test
  void fragmentedToolsAndChineseText()throws Exception{
    response.set("data: {\"choices\":[{\"delta\":{\"content\":\"中文\",\"reasoning_content\":\"内部\",\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"sql_check\",\"arguments\":\"{\\\"sql\\\":\"}}]}}]}\n\n"+
      "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"SELECT 1\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n");
    var tools=mapper.createArrayNode();var definition=tools.addObject().put("type","function").putObject("function").put("name","sql_check").put("description","Validate SQL");
    var parameters=definition.putObject("parameters").put("type","object");parameters.putObject("properties").putObject("sql").put("type","string");parameters.putArray("required").add("sql");
    var content=new StringBuilder();var out=client.stream(model,"",mapper.createArrayNode(),tools,content::append);
    assertThat(mapper.readTree(request.get()).path("tools")).isEqualTo(tools);
    assertThat(content.toString()).isEqualTo("中文");assertThat(out.at("/tool_calls/0/function/arguments").asText()).isEqualTo("{\"sql\":\"SELECT 1\"}");assertThat(mapper.readTree(request.get()).path("stream").asBoolean()).isTrue();
  }
  /** 单步七个完整工具不再被旧次数预算拒绝，仍验证每个调用的完整参数。 */
  @Test
  void acceptsMoreThanSixToolsInOneStep()throws Exception{
    var message=mapper.createObjectNode().put("role","assistant").put("content","");var calls=message.putArray("tool_calls");
    for(int n=0;n<7;n++)calls.addObject().put("id","call_"+n).put("type","function").putObject("function").put("name","context_read").put("arguments","{}");
    var body=mapper.createObjectNode();body.putArray("choices").addObject().put("finish_reason","tool_calls").set("message",message);response.set(body.toString());
    assertThat(client.stream(model,"",mapper.createArrayNode(),mapper.createArrayNode(),text->{}).path("tool_calls")).hasSize(7);
    var delta=mapper.createObjectNode();var parts=delta.putArray("tool_calls");for(int n=0;n<7;n++){var call=(ObjectNode)calls.get(n).deepCopy();call.put("index",n);parts.add(call);}
    var event=mapper.createObjectNode();event.putArray("choices").addObject().put("finish_reason","tool_calls").set("delta",delta);response.set("data: "+event+"\n\ndata: [DONE]\n\n");
    assertThat(client.stream(model,"",mapper.createArrayNode(),mapper.createArrayNode(),text->{}).path("tool_calls")).hasSize(7);
  }
  /** 半截参数或缺少明确终止状态时不能返回可执行工具消息。 */
  @Test
  void rejectsInterruptedTools(){response.set("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"sql_check\",\"arguments\":\"{\"}}]}}]}\n\n");assertThatThrownBy(()->client.stream(model,"",mapper.createArrayNode(),mapper.createArrayNode(),text->{})).isInstanceOf(IllegalStateException.class);}
  /** 兼容非流式厂商响应，也必须验证 finish_reason，长度截断不得执行。 */
  @Test
  void nonStreamingFallbackStillRequiresCompleteFinish()throws Exception{
    response.set("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"答复\"},\"finish_reason\":\"stop\"}]}");var output=client.stream(model,"",mapper.createArrayNode(),mapper.createArrayNode(),text->{});assertThat(output.path("streamed").asBoolean()).isFalse();
    response.set("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"部分\"},\"finish_reason\":\"length\"}]}");assertThatThrownBy(()->client.stream(model,"",mapper.createArrayNode(),mapper.createArrayNode(),text->{})).isInstanceOf(IllegalStateException.class);
  }
  /** 框架迁移后仍只在当前请求链回传供应商推理字段。 */
  @Test
  void preservesReasoningInRequestChain()throws Exception{
    response.set("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"完成\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");
    var history=mapper.createArrayNode();history.addObject().put("role","assistant").put("content","").put("reasoning_content","只用于本次请求链的推理");history.addObject().put("role","user").put("content","继续");
    client.stream(model,"",history,mapper.createArrayNode(),text->{});assertThat(mapper.readTree(request.get()).at("/messages/0/reasoning_content").asText()).isEqualTo("只用于本次请求链的推理");
  }

}
