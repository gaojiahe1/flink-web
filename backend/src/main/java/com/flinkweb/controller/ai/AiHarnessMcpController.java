package com.flinkweb.controller.ai;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.service.ai.AiHarnessMcpService;
import com.flinkweb.dto.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 已登录平台账号的MCP HTTP入口；沿用会话修订、CSRF和角色边界。 */
@RestController
@RequestMapping("/api/ai")
@PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','OPERATOR')")
public class AiHarnessMcpController {
  private final AiHarnessMcpService service;
  private final com.fasterxml.jackson.databind.ObjectMapper mapper;
  /** 只依赖业务协议，不直接访问领域存储。 */
  public AiHarnessMcpController(AiHarnessMcpService service,com.fasterxml.jackson.databind.ObjectMapper mapper){this.service=service;this.mapper=mapper;}
  /** 无状态Streamable HTTP的JSON响应形式；不支持GET事件流或服务器订阅。 */
  @PostMapping(value="/mcp",consumes=MediaType.APPLICATION_JSON_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<ObjectNode> rpc(@RequestBody String body,HttpServletRequest request){
    String origin=request.getHeader("Origin"),version=request.getHeader("MCP-Protocol-Version");
    if(origin!=null){try{var uri=URI.create(origin);int port=uri.getPort()<0?(uri.getScheme().equals("https")?443:80):uri.getPort();if(!uri.getScheme().equals(request.getScheme())||!uri.getHost().equalsIgnoreCase(request.getServerName())||port!=request.getServerPort())return ResponseEntity.status(403).build();}catch(RuntimeException ex){return ResponseEntity.status(403).build();}}
    if(version!=null&&!version.equals("2025-11-25"))return ResponseEntity.badRequest().build();
    if(body.length()>32768)return ResponseEntity.status(413).build();
    ObjectNode input;
    try{var parsed=mapper.readTree(body);if(!(parsed instanceof ObjectNode))return protocolError(-32600,"请求必须为JSON-RPC对象");input=(ObjectNode)parsed;}
    catch(com.fasterxml.jackson.core.JsonProcessingException ex){return protocolError(-32700,"JSON解析失败");}
    var out=service.dispatch(input,true);
    return out==null?ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build():ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
  }
  /** HTTP解析失败仍返回标准JSON-RPC错误，不返回堆栈。 */
  private ResponseEntity<ObjectNode> protocolError(int code,String message){var out=mapper.createObjectNode().put("jsonrpc","2.0").putNull("id");out.putObject("error").put("code",code).put("message",message);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);}
  /** 获取本账号明确维护的偏好。 */
  @GetMapping("/harness/preferences")
  public ApiResponse<ObjectNode> preferences(){return ApiResponse.ok(service.preferences());}
}
