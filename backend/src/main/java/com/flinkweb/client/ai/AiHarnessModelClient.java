package com.flinkweb.client.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** 有界的原生工具及流式模型协议；不执行工具、不记录密钥或厂商内部推理。 */
@Component
public class AiHarnessModelClient {
  private final ObjectMapper mapper;
  private final AiModelClient endpoints;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
      .followRedirects(HttpClient.Redirect.NEVER).build();

  /** 复用已登记模型地址的网络限制。 */
  public AiHarnessModelClient(ObjectMapper mapper, AiModelClient endpoints) {
    this.mapper=mapper; this.endpoints=endpoints;
  }

  /** 完整收齐工具参数后返回；文本增量仅交给调用方做安全投影。 */
  public ObjectNode stream(ObjectNode model, String key, ArrayNode messages, ArrayNode tools,
      Consumer<String> text) throws Exception {
    var target=endpoints.endpoint(model.path("baseUrl").asText(),model.path("privateNetwork").asBoolean());
    var body=mapper.createObjectNode().put("model",model.path("model").asText()).put("stream",true)
        .put("max_tokens",model.path("maxTokens").asInt(4096));
    body.set("messages",messages); if(!tools.isEmpty())body.set("tools",tools);
    if("api-inference.modelscope.cn".equalsIgnoreCase(target.getHost())
        && model.path("model").asText().startsWith("Qwen/Qwen3"))body.put("enable_thinking",false);
    int seconds=model.path("timeoutSeconds").asInt(90);
    var request=HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(seconds))
        .header("Content-Type","application/json");
    if(!key.isBlank())request.header("Authorization","Bearer "+key);
    var subscriber=new StreamBody(text);
    var pending=http.sendAsync(request.POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
        info->info.statusCode()>=200&&info.statusCode()<300?subscriber:
            HttpResponse.BodySubscribers.replacing(mapper.createObjectNode()));
    try {
      var response=pending.get(seconds,TimeUnit.SECONDS);
      if(response.statusCode()<200||response.statusCode()>=300)
        throw new IllegalStateException("模型服务返回 HTTP "+response.statusCode()+"，请检查模型权限、配额及工具协议支持");
      return response.body();
    } finally { subscriber.cancel();pending.cancel(true); }
  }

  /** 按完整 SSE 帧解码 UTF-8，处理分片参数、长度预算和中断。 */
  private final class StreamBody implements HttpResponse.BodySubscriber<ObjectNode> {
    private final CompletableFuture<ObjectNode> result=new CompletableFuture<>();
    private final ByteArrayOutputStream line=new ByteArrayOutputStream(), raw=new ByteArrayOutputStream();
    private final StringBuilder event=new StringBuilder(),content=new StringBuilder(),reasoning=new StringBuilder();
    private final SortedMap<Integer,ObjectNode> calls=new TreeMap<>();
    private final Consumer<String> text;
    private Flow.Subscription subscription;
    private long bytes;
    private boolean json,detected;
    private String finish="";
    private ObjectNode usage;

    /** 保存本轮文本接收者，状态仅存在于本次请求。 */
    StreamBody(Consumer<String> text){this.text=text;}
    /** 暴露完整消息的完成信号。 */
    public CompletionStage<ObjectNode> getBody(){return result;}
    /** 逐批背压读取，不创建无界队列。 */
    public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
    /** 网络分片不等于事件边界，累计到换行及空行再解码。 */
    public void onNext(List<ByteBuffer> buffers){
      try {
        for(var buffer:buffers)while(buffer.hasRemaining()){
          int value=buffer.get()&255;
          if(++bytes>1024*1024)throw new IllegalStateException("模型流式响应超过大小限制");
          if(!detected&&!Character.isWhitespace(value)){json=value=='{';detected=true;}
          if(json)raw.write(value);
          else if(value=='\n')line(); else {line.write(value);if(line.size()>262144)throw new IllegalStateException("模型事件过长");}
        }
        subscription.request(1);
      }catch(Exception error){result.completeExceptionally(error);cancel();}
    }
    /** 传递协议或网络失败，不回显厂商响应正文。 */
    public void onError(Throwable error){result.completeExceptionally(error);}
    /** 缺少明确终止状态的断流不能执行部分工具调用。 */
    public void onComplete(){
      try {
        if(json){var parsed=mapper.readTree(raw.toByteArray());if(!(parsed.at("/choices/0/message") instanceof ObjectNode message))throw new IllegalStateException("模型响应缺少消息");
          validate(message,parsed.at("/choices/0/finish_reason").asText());
          String value=message.path("content").asText("");if(value.length()>32768)throw new IllegalStateException("模型回复过长");
          text.accept(value);var output=message.deepCopy().put("streamed",false);if(parsed.path("usage").isObject())output.set("usage",parsed.path("usage"));result.complete(output);return;}
        if(line.size()>0)line();frame();
        if(!Set.of("stop","tool_calls").contains(finish))throw new IllegalStateException("模型输出未完整结束，请核对原任务后重试");
        if(finish.equals("tool_calls")&&calls.isEmpty())throw new IllegalStateException("模型缺少完整工具调用");
        var message=mapper.createObjectNode().put("role","assistant").put("content",content.toString()).put("streamed",true);
        if(!reasoning.isEmpty())message.put("reasoning_content",reasoning.toString());
        if(!calls.isEmpty()){
          var output=message.putArray("tool_calls");var ids=new HashSet<String>();
          for(var call:calls.values()){
            if(!call.path("id").asText().matches("[A-Za-z0-9_-]{1,200}")||!ids.add(call.path("id").asText())
                ||!call.at("/function/name").asText().matches("[A-Za-z0-9_]{1,80}"))throw new IllegalStateException("模型工具标识无效");
            mapper.readTree(call.at("/function/arguments").asText());output.add(call);
          }
        }
        validate(message,finish);if(usage!=null)message.set("usage",usage);result.complete(message);
      }catch(Exception error){result.completeExceptionally(error);}
    }
    /** 非流式兼容与 SSE 使用相同的终止、数量及完整参数边界。 */
    private void validate(ObjectNode message,String reason)throws Exception{
      var tools=message.path("tool_calls");if(!Set.of("stop","tool_calls").contains(reason)||reason.equals("tool_calls")==tools.isEmpty())throw new IllegalStateException("模型输出未完整结束");
      if(!tools.isMissingNode()&&!tools.isNull()&&!tools.isArray())throw new IllegalStateException("工具调用格式无效");
      if(message.path("reasoning_content").asText().length()>65536)throw new IllegalStateException("模型输出超过文本预算");
      var ids=new HashSet<String>();for(var call:tools){if(!call.path("id").asText().matches("[A-Za-z0-9_-]{1,200}")||!ids.add(call.path("id").asText())||!call.at("/function/name").asText().matches("[A-Za-z0-9_]{1,80}")||!call.path("type").asText().equals("function")||call.at("/function/arguments").asText().length()>32768||!(mapper.readTree(call.at("/function/arguments").asText()) instanceof ObjectNode))throw new IllegalStateException("工具调用参数无效");}
    }
    /** 只解析完整行，拒绝损坏的 UTF-8。 */
    private void line()throws Exception{
      String value=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(line.toByteArray())).toString();line.reset();
      if(value.endsWith("\r"))value=value.substring(0,value.length()-1);
      if(value.isEmpty())frame();else if(value.startsWith("data:")){if(!event.isEmpty())event.append('\n');event.append(value.substring(5).stripLeading());}
    }
    /** 工具增量只组装，不触发任何执行。 */
    private void frame()throws Exception{
      if(event.isEmpty())return;String data=event.toString();event.setLength(0);if(data.equals("[DONE]"))return;
      var parsed=mapper.readTree(data);if(parsed.has("error"))throw new IllegalStateException("模型流式协议返回错误");
      if(parsed.path("usage").isObject())usage=(ObjectNode)parsed.path("usage").deepCopy();
      var choice=parsed.at("/choices/0");var delta=choice.path("delta");
      if(choice.hasNonNull("finish_reason"))finish=choice.path("finish_reason").asText();
      String value=delta.path("content").asText("");content.append(value);reasoning.append(delta.path("reasoning_content").asText(""));
      if(content.length()>32768||reasoning.length()>65536)throw new IllegalStateException("模型输出超过文本预算");
      if(!value.isEmpty())text.accept(value);
      for(var part:delta.path("tool_calls")){
        int index=part.path("index").asInt(-1);if(index<0)throw new IllegalStateException("模型工具索引无效");
        var call=calls.computeIfAbsent(index,k->{var n=mapper.createObjectNode().put("id","").put("type","function");n.putObject("function").put("name","").put("arguments","");return n;});
        if(part.hasNonNull("id"))call.put("id",call.path("id").asText()+part.path("id").asText());
        var fn=call.withObject("/function");for(String field:List.of("name","arguments"))if(part.path("function").hasNonNull(field))fn.put(field,fn.path(field).asText()+part.path("function").path(field).asText());
        if(fn.path("arguments").asText().length()>32768)throw new IllegalStateException("工具参数过长");
      }
    }
    /** 取消上游读取，使超时、删除及取消不继续消耗连接。 */
    void cancel(){if(subscription!=null)subscription.cancel();}
  }
}
