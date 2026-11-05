package com.flinkweb.service.ai.impl;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.service.ai.AiHarnessMcpService;
import com.flinkweb.service.ai.support.*;
import com.flinkweb.service.audit.AuditService;
import com.flinkweb.exception.common.BusinessException;
import org.springframework.stereotype.Service;

/** 有限的无状态MCP协议配置，复用平台登录和CSRF，不创建匿名或代理身份。 */
@Service
public class AiHarnessMcpServiceImpl implements AiHarnessMcpService {
  private final AiHarnessCapabilities capabilities;
  private final AiHarnessPreferences prefs;
  private final ObjectMapper mapper;
  private final AuditService audit;
  /** 注入共用能力目录，不经过自HTTP调用平台接口。 */
  public AiHarnessMcpServiceImpl(AiHarnessCapabilities capabilities,AiHarnessPreferences prefs,ObjectMapper mapper,AuditService audit){this.capabilities=capabilities;this.prefs=prefs;this.mapper=mapper;this.audit=audit;}
  /** 初始化明确支持的协议版本，工具业务失败用isError，协议失败用JSON-RPC错误。 */
  public ObjectNode dispatch(ObjectNode request,boolean allowLogs){
    JsonNode id=request.path("id");var response=mapper.createObjectNode().put("jsonrpc","2.0");response.set("id",id.isMissingNode()?NullNode.instance:id);
    if(!request.path("jsonrpc").asText().equals("2.0")||!request.path("method").isTextual()||request.has("id")&&!id.isTextual()&&!id.isNumber()&&!id.isNull())return error(response,-32600,"无效JSON-RPC请求");
    String method=request.path("method").asText();
    if(!request.has("id"))return null;
    if(request.toString().length()>32768)return error(response,-32602,"请求超过大小预算");
    var params=request.path("params");if(!params.isMissingNode()&&!params.isObject())return error(response,-32602,"params必须为对象");
    ObjectNode result;
    try{
      switch(method){
        case "initialize" -> {result=mapper.createObjectNode().put("protocolVersion","2025-11-25");result.putObject("serverInfo").put("name","flink-web").put("version","0.0.10");var caps=result.putObject("capabilities");caps.putObject("tools").put("listChanged",false);caps.putObject("resources").put("subscribe",false).put("listChanged",false);caps.putObject("prompts").put("listChanged",false);result.put("instructions","使用平台登录会话及CSRF；只读工具，日志需请求头X-Flink-Log-Consent: true；写操作在平台对话内审阅实施。");}
        case "ping" -> result=mapper.createObjectNode();
        case "tools/list" -> {result=mapper.createObjectNode();result.set("tools",capabilities.tools());}
        case "tools/call" -> {if(!params.path("name").isTextual()||params.has("arguments")&&!params.path("arguments").isObject())return error(response,-32602,"需要工具名称与对象参数");result=mapper.createObjectNode();try{var data=capabilities.call(params.path("name").asText(),params.has("arguments")?(ObjectNode)params.path("arguments"):mapper.createObjectNode(),"",allowLogs,"");result.set("structuredContent",data);result.putArray("content").addObject().put("type","text").put("text",data.toString());result.put("isError",false);audit.record("AI_MCP_TOOL",params.path("name").asText(),"SUCCESS");}catch(BusinessException ex){result.put("isError",true);result.putArray("content").addObject().put("type","text").put("text",ex.code()+": "+ex.getMessage());audit.record("AI_MCP_TOOL",params.path("name").asText(),"FAILED");}}
        case "resources/list" -> {result=mapper.createObjectNode();result.set("resources",capabilities.resources().path("resources"));}
        case "resources/templates/list" -> {result=mapper.createObjectNode();result.set("resourceTemplates",capabilities.resources().path("resourceTemplates"));}
        case "resources/read" -> {if(!params.path("uri").isTextual())return error(response,-32602,"需要资源URI");var data=capabilities.resource(params.path("uri").asText(),allowLogs,"");result=mapper.createObjectNode();result.putArray("contents").addObject().put("uri",params.path("uri").asText()).put("mimeType","application/json").put("text",data.toString());}
        case "prompts/list" -> {result=capabilities.prompts();for(var item:result.path("prompts"))((ObjectNode)item).remove("version");}
        case "prompts/get" -> {if(!params.path("name").isTextual()||params.has("arguments")&&(!params.path("arguments").isObject()||!params.path("arguments").isEmpty()))return error(response,-32602,"模板名称或参数无效");result=capabilities.prompt(params.path("name").asText());result.remove("version");}
        default -> {return error(response,-32601,"未支持该MCP方法");}
      }
    }catch(BusinessException ex){return error(response,-32602,ex.code()+": "+ex.getMessage());}catch(RuntimeException ex){return error(response,-32603,"读取失败；请核对资源、权限或服务可用性");}
    response.set("result",result);return response;
  }
  /** 协议错误不包含原始上游响应或凭据。 */
  private ObjectNode error(ObjectNode response,int code,String message){response.putObject("error").put("code",code).put("message",message);return response;}
  /** 当前账号读取自己的偏好。 */
  public ObjectNode preferences(){return prefs.get();}
}
