package com.flinkweb.service.ai.support;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.permission.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.context.event.EventListener;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 固定本机HTTP复用完整MVC/方法安全链；短期一次性委托不进入模型、数据库或日志。 */
@Component
public class AiApiTransport {
  public static final String ATTRIBUTE=AiApiTransport.class.getName()+".authorized";
  private final AiPlatformApiCatalog catalog;private final ObjectMapper mapper;private final UserService users;private final RecordRepository records;private final AiContentGuard guard;
  private final ConcurrentMap<String,Ticket> tickets=new ConcurrentHashMap<>();private volatile int port;
  private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
  /** 只保存委托身份及确切方法/路径，令牌十秒有效并原子消费。 */
  private record Ticket(String actor,long revision,String method,String uri,Instant expires) {}
  /** 注入目录和当前账号服务，不接受任意上游URL。 */
  public AiApiTransport(AiPlatformApiCatalog catalog,ObjectMapper mapper,UserService users,RecordRepository records,AiContentGuard guard){this.catalog=catalog;this.mapper=mapper;this.users=users;this.records=records;this.guard=guard;}
  /** 使用容器实际端口，支持随机端口测试和非默认部署。 */
  @EventListener public void started(WebServerInitializedEvent event){port=event.getWebServer().getPort();}
  /** 读请求直接执行，其他请求仅冻结待审阅内容。 */
  public ObjectNode call(String name,ObjectNode args,String session,String turn){
    var op=catalog.require(name);validate(args);String uri=uri(op,args);guard.requireSafe(args.toString());if(uri.startsWith("/api/catalog/configs/")&&args.at("/body/kind").asText().equals("secret")&&!args.at("/body/value").asText().isBlank()||args.at("/body/apiKey").isTextual()&&!args.at("/body/apiKey").asText().isBlank())throw new BusinessException(HttpStatus.BAD_REQUEST,"MCP_SECRET_WRITE_DENIED","密钥请在原配置页面填写，MCP仅使用已登记引用");
    if(op.path().contains("download")||java.util.Arrays.stream(op.handler().getMethodParameters()).anyMatch(p->org.springframework.web.multipart.MultipartFile.class.isAssignableFrom(p.getParameterType())))return mapper.createObjectNode().put("status","BROWSER_REQUIRED").put("message","已发现此接口；二进制下载/文件上传请通过原页面完成");
    if(op.direct())return send(op,uri,args.path("body"),args.path("offset").asInt());
    var node=mapper.createObjectNode().put("owner",actor()).put("actorRevision",users.revision(actor())).put("tool",name).put("method",op.method()).put("uri",uri).put("sessionId",session).put("turnId",turn).put("state","PENDING").put("expiresAt",Instant.now().plusSeconds(1800).toString());node.set("arguments",args.deepCopy());var saved=records.save("ai_api_proposals",UUID.randomUUID().toString(),node,0);var out=mapper.createObjectNode().put("status","REVIEW_REQUIRED").put("proposalId",saved.path("id").asText()).put("reviewUrl","/#/ai?operation="+saved.path("id").asText());out.set("review",view(saved));return out;
  }
  /** 所有人均只能读取自己的请求，显示服务端冻结内容而非浏览器回传内容。 */
  public ObjectNode proposal(String id){var row=records.require("ai_api_proposals",id);owner(row);return view(row);}
  /** 审阅显示完整参数；凭据赋值在提案时被禁止。 */
  private ObjectNode view(ObjectNode row){var out=row.deepCopy();out.remove(List.of("owner","actorRevision"));return (ObjectNode)guard.sanitize(out);}
  /** 确认只执行服务端原提案；先持久化UNKNOWN，重试永不重复写，网络不入数据库事务。 */
  public synchronized ObjectNode execute(String id,boolean confirmed){
    var row=records.require("ai_api_proposals",id);owner(row);var op=catalog.require(row.path("tool").asText());if(!confirmed)throw new BusinessException(HttpStatus.BAD_REQUEST,"MCP_CONFIRM_REQUIRED","请审阅具体请求后确认");
    if(!row.path("state").asText().equals("PENDING"))return view(row);
    if(!row.path("sessionId").asText().isBlank()){var session=records.require("ai_harness_sessions",row.path("sessionId").asText());var turn=records.require("ai_harness_turns",row.path("turnId").asText());if(!session.path("owner").asText().equals(actor())||!session.path("epoch").equals(turn.path("epoch"))||session.path("sequence").asLong()!=turn.path("sequence").asLong()||!turn.path("state").asText().equals("WAITING_USER")||!turn.at("/interaction/proposalId").asText().equals(id))throw new BusinessException(HttpStatus.CONFLICT,"MCP_PROPOSAL_STALE","原对话已取消或发生变化，请重新审阅");}
    if(users.revision(actor())!=row.path("actorRevision").asLong()||Instant.parse(row.path("expiresAt").asText()).isBefore(Instant.now()))throw new BusinessException(HttpStatus.CONFLICT,"MCP_PROPOSAL_EXPIRED","账号或请求已变化，请重新审阅");
    row.put("state","UNKNOWN");row=records.save("ai_api_proposals",id,row,row.path("revision").asLong());
    try{var result=send(op,row.path("uri").asText(),row.at("/arguments/body"),0);row.set("result",result);row.put("state",result.path("httpStatus").asInt()<400?"COMPLETED":"REJECTED");records.save("ai_api_proposals",id,row,row.path("revision").asLong());return view(row);}catch(RuntimeException ex){throw new BusinessException(HttpStatus.BAD_GATEWAY,"MCP_RESULT_UNKNOWN","请求结果未知，请核对业务页面和原操作号；不会自动重复执行");}
  }
  /** 归属校验不允许管理员读取其他账号的私有提案。 */
  private void owner(ObjectNode row){catalog.identity();if(!row.path("owner").asText().equals(actor()))throw new BusinessException(HttpStatus.FORBIDDEN,"FORBIDDEN","无权读取该提案");}
  /** 当前线程的真实用户名称，不接受模型传入身份。 */
  private String actor(){return SecurityContextHolder.getContext().getAuthentication().getName();}
  /** 参数对象只允许声明的三段，防止header、URL和身份注入。 */
  private void validate(ObjectNode args){args.fields().forEachRemaining(f->{if(!Set.of("path","query","body","offset").contains(f.getKey())||!(f.getKey().equals("offset")?f.getValue().isInt()&&f.getValue().asInt()>=0:f.getValue().isObject()))throw new BusinessException(HttpStatus.BAD_REQUEST,"MCP_API_ARGS","仅允许path/query/body对象");});if(args.toString().length()>32768)throw new BusinessException(HttpStatus.BAD_REQUEST,"MCP_API_ARGS","请求过大");}
  /** 路径值仅能替换单段标识，查询参数统一编码，拒绝越过登记路由。 */
  private String uri(AiPlatformApiCatalog.Operation op,ObjectNode args){String path=op.path();var fields=args.path("path").fields();while(fields.hasNext()){var field=fields.next();String value=field.getValue().asText();if(!value.matches("[A-Za-z0-9_:.@-]{1,200}")||value.equals("..")||!path.contains("{"+field.getKey()+"}"))throw new BusinessException(HttpStatus.BAD_REQUEST,"MCP_API_PATH","路径参数无效");path=path.replace("{"+field.getKey()+"}",value);}if(path.contains("{"))throw new BusinessException(HttpStatus.BAD_REQUEST,"MCP_API_PATH","缺少路径参数");var parts=new ArrayList<String>();args.path("query").fields().forEachRemaining(f->{if(!f.getValue().isValueNode())throw new BusinessException(HttpStatus.BAD_REQUEST,"MCP_API_QUERY","查询参数须为标量");parts.add(encode(f.getKey())+"="+encode(f.getValue().asText()));});return path+(parts.isEmpty()?"":"?"+String.join("&",parts));}
  /** UTF-8编码，路径和目标主机不由模型决定。 */
  private String encode(String value){return URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8);}
  /** 每次调用重新鉴权，响应上限2MiB并脱敏；二进制接口提示使用原页面。 */
  private ObjectNode send(AiPlatformApiCatalog.Operation op,String uri,JsonNode body,int offset){catalog.require(op.name());tickets.entrySet().removeIf(e->e.getValue().expires().isBefore(Instant.now()));String key=UUID.randomUUID().toString()+UUID.randomUUID();tickets.put(key,new Ticket(actor(),users.revision(actor()),op.method(),uri,Instant.now().plusSeconds(10)));
    try{var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+uri)).timeout(Duration.ofSeconds(60)).header("X-Flink-Internal-Call",key).header("Content-Type","application/json").method(op.method(),body.isMissingNode()?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body.toString())).build();var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());try(var input=response.body()){byte[] bytes=input.readNBytes(2097153);var out=mapper.createObjectNode().put("httpStatus",response.statusCode());if(response.statusCode()>=400)out.put("error","API_HTTP_"+response.statusCode());if(bytes.length>2097152)return out.put("message","响应过大，请通过页面下载或缩小范围");if(!response.headers().firstValue("Content-Type").orElse("").contains("json"))return out.put("message","非JSON响应，请通过原页面获取");var parsed=guard.sanitize(mapper.readTree(bytes));if(parsed.path("data").isArray()){var all=parsed.path("data");var page=mapper.createArrayNode();for(int n=offset;n<Math.min(all.size(),offset+50);n++)page.add(all.get(n));((ObjectNode)parsed).set("data",page);out.put("total",all.size());if(offset+50<all.size())out.put("nextOffset",offset+50);}if(parsed.toString().length()>50000)return out.put("status","RESULT_TOO_LARGE").put("message","结果超过模型上下文预算，请缩小查询条件或在原页面查看");out.set("data",parsed);return out;}}
    catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException("请求中断",ex);}catch(IOException ex){throw new IllegalStateException("业务接口暂不可用",ex);}finally{tickets.remove(key);}
  }
  /** 一次性委托必须来自本机，方法和完整路径匹配，账号修订仍有效。 */
  public OncePerRequestFilter filter(){return new OncePerRequestFilter(){
    /** 委托通过后仍执行原MVC、Bean Validation和方法权限；不创建可复用会话。 */
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException{
      String key=req.getHeader("X-Flink-Internal-Call");if(key==null){chain.doFilter(req,res);return;}var ticket=tickets.remove(key);String uri=req.getRequestURI()+(req.getQueryString()==null?"":"?"+req.getQueryString());if(ticket==null||!InetAddress.getByName(req.getRemoteAddr()).isLoopbackAddress()||!ticket.method().equals(req.getMethod())||!ticket.uri().equals(uri)||ticket.expires().isBefore(Instant.now())||users.revision(ticket.actor())!=ticket.revision()){res.sendError(403);return;}
      var user=users.loadUserByUsername(ticket.actor());if(!user.isEnabled()){res.sendError(403);return;}var context=SecurityContextHolder.createEmptyContext();context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user,null,user.getAuthorities()));SecurityContextHolder.setContext(context);req.setAttribute(ATTRIBUTE,true);try{chain.doFilter(req,res);}finally{SecurityContextHolder.clearContext();}
    }
  };}
}
