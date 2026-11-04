package com.flinkweb.client.ai;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.exception.common.BusinessException;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.model.NoopApiKey;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.*;
import org.springframework.retry.support.RetryTemplate;
import reactor.core.publisher.Flux;

/** 全项目共用Spring AI模型调用；平台仅维护安全传输边界和领域消息适配。 */
public class SpringAiModelGateway {
  private final ObjectMapper mapper;
  private final AiTelemetry telemetry;
  /** JSON与Spring AI消息之间保持显式契约。 */
  public SpringAiModelGateway(ObjectMapper mapper,AiTelemetry telemetry){this.mapper=mapper;this.telemetry=telemetry;}
  /** 每次请求独立配置模型，支持数据库中的多模型和密钥修订，无自动重试或工具执行。 */
  public ObjectNode call(ObjectNode model,String key,URI endpoint,ArrayNode history,ArrayNode tools,Consumer<String> consumer)throws Exception {
    try(var trace=telemetry.start("ai.generation","generation",Map.of("gen_ai.request.model",model.path("model").asText(),"gen_ai.system","openai","gen_ai.operation.name","chat"))){
      var first=new AtomicBoolean();Consumer<String> observed=consumer==null?null:value->{if(first.compareAndSet(false,true))trace.attribute("langfuse.observation.completion_start_time",java.time.Instant.now().toString());consumer.accept(value);};
      try{var out=invoke(model,key,endpoint,history,tools,observed);for(String field:List.of("prompt_tokens","completion_tokens","total_tokens"))if(out.path("usage").has(field))trace.usage("gen_ai.usage."+switch(field){case "prompt_tokens"->"input_tokens";case "completion_tokens"->"output_tokens";default->"total_tokens";},out.path("usage").path(field).asLong());return out;}
      catch(Exception ex){trace.error(ex instanceof BusinessException business?business.code():ex.getClass().getSimpleName());throw ex;}
    }
  }
  /** 框架负责序列化和流式合并，保留平台自己的超时与网络限制。 */
  private ObjectNode invoke(ObjectNode model,String key,URI endpoint,ArrayNode history,ArrayNode tools,Consumer<String> consumer)throws Exception {
    int seconds=model.path("timeoutSeconds").asInt(90);if(seconds<1||seconds>600)throw new IllegalArgumentException("模型超时范围无效");
    var transport=new SpringAiTransport(mapper,model,seconds);
    var api=new ReasoningApi(endpoint,key,transport.rest(),transport.web(),history);

    var options=OpenAiChatOptions.builder().model(model.path("model").asText()).maxTokens(model.path("maxTokens").asInt(4096)).internalToolExecutionEnabled(false).streamUsage(consumer!=null);
    if(!tools.isEmpty()){var functions=new ArrayList<OpenAiApi.FunctionTool>();for(var tool:tools)functions.add(mapper.treeToValue(tool,OpenAiApi.FunctionTool.class));options.tools(functions);}
    if(model.path("baseUrl").asText().contains("api-inference.modelscope.cn")&&model.path("model").asText().startsWith("Qwen/Qwen3"))options.extraBody(Map.of("enable_thinking",false));
    var chat=OpenAiChatModel.builder().openAiApi(api).defaultOptions(options.build()).retryTemplate(RetryTemplate.builder().maxAttempts(1).build()).build();
    var prompt=new Prompt(messages(history));var result=mapper.createObjectNode().put("role","assistant").put("content","");
    if(consumer==null){var response=chat.call(prompt);accept(result,response,null);result.put("streamed",false);}
    else {
      var security=org.springframework.security.core.context.SecurityContextHolder.getContext();
      try(var responses=chat.stream(prompt).takeUntilOther(reactor.core.publisher.Mono.delay(Duration.ofSeconds(seconds)).flatMap(n->reactor.core.publisher.Mono.error(new java.util.concurrent.TimeoutException("模型请求超过截止时间")))).toStream()){responses.forEach(response->new org.springframework.security.concurrent.DelegatingSecurityContextRunnable(()->accept(result,response,consumer),security).run());}
      result.put("streamed",transport.streamed());
    }
    validate(result);result.remove("finishReason");return result;
  }
  /** 保留system/user/assistant/tool消息和工具请求关联，不把工具结果当用户指令。 */
  private List<Message> messages(ArrayNode source){var out=new ArrayList<Message>();for(var item:source){String text=item.path("content").asText("");switch(item.path("role").asText()){
    case "system"->out.add(new SystemMessage(text));case "user"->out.add(new UserMessage(text));
    case "assistant"->{var calls=new ArrayList<AssistantMessage.ToolCall>();for(var call:item.path("tool_calls"))calls.add(new AssistantMessage.ToolCall(call.path("id").asText(),"function",call.at("/function/name").asText(),call.at("/function/arguments").asText()));out.add(AssistantMessage.builder().content(text).toolCalls(calls).build());}
    case "tool"->out.add(ToolResponseMessage.builder().responses(List.of(new ToolResponseMessage.ToolResponse(item.path("tool_call_id").asText(),item.path("name").asText(""),text))).build());
    default->throw new IllegalArgumentException("模型消息角色无效");
  }}return out;}
  /** 框架已合并工具分片；只投影明确字段，不暴露响应正文或内部推理到观测系统。 */
  private void accept(ObjectNode out,ChatResponse response,Consumer<String> consumer){
    if(response==null)return;var generation=response.getResult();if(generation!=null){var answer=generation.getOutput();String text=answer.getText();if(text!=null&&!text.isEmpty()){out.put("content",out.path("content").asText()+text);if(consumer!=null)consumer.accept(text);}
      String finish=generation.getMetadata().getFinishReason();if(finish!=null&&!finish.isBlank())out.put("finishReason",finish.toLowerCase(Locale.ROOT));
      if(answer.hasToolCalls()){var calls=out.withArray("tool_calls");for(var call:answer.getToolCalls()){var node=calls.addObject().put("id",call.id()).put("type",call.type());node.putObject("function").put("name",call.name()).put("arguments",call.arguments());}}
      Object reasoning=answer.getMetadata().get("reasoningContent");if(reasoning instanceof String value&&!value.isEmpty())out.put("reasoning_content",out.path("reasoning_content").asText()+value);
    }
    var usage=response.getMetadata().getUsage();if(usage!=null&&usage.getTotalTokens()!=null&&usage.getTotalTokens()>0)out.putObject("usage").put("prompt_tokens",usage.getPromptTokens()).put("completion_tokens",usage.getCompletionTokens()).put("total_tokens",usage.getTotalTokens());
    if(out.path("content").asText().length()>32768||out.path("reasoning_content").asText().length()>65536)throw new IllegalStateException("模型输出超过文本预算");
  }
  /** 未明确完成、重复工具标识或不完整JSON参数都不能交付执行。 */
  private void validate(ObjectNode result)throws Exception {String finish=result.path("finishReason").asText();var calls=result.path("tool_calls");if(!Set.of("stop","tool_calls").contains(finish)||finish.equals("tool_calls")==calls.isEmpty())throw new IllegalStateException("模型输出未完整结束");var ids=new HashSet<String>();for(var call:calls)if(!call.path("id").asText().matches("[A-Za-z0-9_-]{1,200}")||!ids.add(call.path("id").asText())||!call.at("/function/name").asText().matches("[A-Za-z0-9_]{1,80}")||call.at("/function/arguments").asText().length()>32768||!(mapper.readTree(call.at("/function/arguments").asText()) instanceof ObjectNode))throw new IllegalStateException("工具调用参数无效");}
  /** 保留供应商要求回传的推理字段，仅在请求链内使用，不进入持久化或观测。 */
  private final class ReasoningApi extends OpenAiApi {
    private final ArrayNode history;
    /** 显式注入受控HTTP配置，框架继续负责消息序列化及工具分片。 */
    private ReasoningApi(URI endpoint,String key,RestClient.Builder rest,WebClient.Builder web,ArrayNode history){super(endpoint.getScheme()+"://"+endpoint.getRawAuthority(),key.isBlank()?new NoopApiKey():new org.springframework.ai.model.SimpleApiKey(key),new org.springframework.util.LinkedMultiValueMap<>(),endpoint.getRawPath(),"/v1/embeddings",rest,web,org.springframework.ai.retry.RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);this.history=history.deepCopy();}
    /** 只为原assistant消息补回对应字段，不允许覆盖角色、目标或工具定义。 */
    private ChatCompletionRequest reasoning(ChatCompletionRequest request){var tree=(ObjectNode)mapper.valueToTree(request);for(int n=0;n<history.size()&&n<tree.path("messages").size();n++)if(history.get(n).path("role").asText().equals("assistant")&&history.get(n).has("reasoning_content"))((ObjectNode)tree.path("messages").get(n)).put("reasoning_content",history.get(n).path("reasoning_content").asText());return mapper.convertValue(tree,ChatCompletionRequest.class);}
    /** 同步请求保持推理回传兼容。 */
    @Override public org.springframework.http.ResponseEntity<ChatCompletion> chatCompletionEntity(ChatCompletionRequest request,org.springframework.util.MultiValueMap<String,String> headers){return super.chatCompletionEntity(reasoning(request),headers);}
    /** 流式请求保持推理回传兼容，解析仍由Spring AI执行。 */
    @Override public Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest request,org.springframework.util.MultiValueMap<String,String> headers){return super.chatCompletionStream(reasoning(request),headers);}
  }

}
