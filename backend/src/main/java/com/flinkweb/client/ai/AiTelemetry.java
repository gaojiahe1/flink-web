package com.flinkweb.client.ai;
import io.opentelemetry.api.*;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.*;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** AI专属观测边界；正文按开关脱敏限长，失败不能阻断业务，不导出内部推理。 */
@Component
public class AiTelemetry {
  private static final ContextKey<Map<String,String>> ATTRIBUTES=ContextKey.named("flink-ai-trace");
  private final Tracer tracer;
  private final ObjectMapper mapper;
  private final ContentSanitizer sanitizer;
  private final boolean captureContent;
  private static final int MAX_CONTENT=32768;
  /** 内容策略由配置层提供，客户端不依赖领域服务或持久化。 */
  public interface ContentSanitizer {
    /** 返回可导出的安全副本，不能修改业务输入。 */
    JsonNode sanitize(JsonNode value);
  }
  /** 注入可禁用的SDK，未配置Langfuse不影响应用启动。 */
  @org.springframework.beans.factory.annotation.Autowired
  public AiTelemetry(OpenTelemetry telemetry,ObjectMapper mapper,ContentSanitizer sanitizer,
      @org.springframework.beans.factory.annotation.Value("${flink-web.ai.telemetry.capture-content:true}") boolean captureContent){
    tracer=telemetry.getTracer("flink-web-ai","0.0.10");this.mapper=mapper;this.sanitizer=sanitizer;this.captureContent=captureContent;
  }
  /** 未显式提供脱敏策略的测试/调用保持元数据模式，不能直接导出正文。 */
  public AiTelemetry(OpenTelemetry telemetry){this(telemetry,new ObjectMapper(),value->value,false);}
  /** 非Spring单元测试沿用无网络实现。 */
  public static AiTelemetry noop(){return new AiTelemetry(OpenTelemetry.noop());}
  /** 每个子观测复制会话、任务和账号属性，满足Langfuse v4的过滤要求。 */
  public Trace start(String name,String type,Map<String,String> metadata){
    var attrs=new HashMap<String,String>();var inherited=Context.current().get(ATTRIBUTES);if(inherited!=null)attrs.putAll(inherited);attrs.putAll(metadata);attrs.putIfAbsent("langfuse.trace.name",name);attrs.put("langfuse.release","0.0.10");
    var span=tracer.spanBuilder(name).startSpan();attrs.forEach(span::setAttribute);span.setAttribute("langfuse.observation.type",type);
    var context=Context.current().with(span).with(ATTRIBUTES,Map.copyOf(attrs));return new Trace(span,context.makeCurrent(),this);
  }
  /** 完整脱敏后才限长；超预算返回带标记的有效JSON，不截断凭据或代理对。 */
  private void content(Span span,String direction,Object value){
    if(!captureContent||!span.isRecording())return;
    try{
      String text=mapper.writeValueAsString(sanitizer.sanitize(mapper.valueToTree(value)));
      if(text.length()>MAX_CONTENT){
        int end=(MAX_CONTENT-200)/2;if(Character.isHighSurrogate(text.charAt(end-1)))end--;
        text=mapper.createObjectNode().put("truncated",true).put("originalCharacters",text.length()).put("preview",text.substring(0,end)).toString();
        span.setAttribute("flink.telemetry."+direction+".truncated",true);
      }
      span.setAttribute("langfuse.observation."+direction,text);
    }catch(RuntimeException|com.fasterxml.jackson.core.JsonProcessingException failure){span.setAttribute("flink.telemetry."+direction+".unavailable",true);}
  }
  /** 一段受控执行的生命周期，错误只记录分类，禁止记录原始异常正文。 */
  public static final class Trace implements AutoCloseable {
    private final Span span;private final Scope scope;private final AiTelemetry telemetry;
    /** 保留对应当前线程的scope，必须由创建线程结算。 */
    private Trace(Span span,Scope scope,AiTelemetry telemetry){this.span=span;this.scope=scope;this.telemetry=telemetry;}
    /** 返回关联标识供页面定位，不接受用户伪造父轨迹。 */
    public String id(){return span.isRecording()&&span.getSpanContext().isValid()?span.getSpanContext().getTraceId():"";}
    /** 仅允许调用方传入已确定的元数据，不传原始模型输入输出。 */
    public void attribute(String name,String value){span.setAttribute(name,value);}
    /** 模型usage使用真实值，缺失时不补造。 */
    public void usage(String key,long value){span.setAttribute(key,value);}
    /** 输入必须经过统一脱敏和预算后才能进入Langfuse。 */
    public void input(Object value){telemetry.content(span,"input",value);}
    /** 仅记录完整结果或受控错误分类，流式分片不逐段外发。 */
    public void output(Object value){telemetry.content(span,"output",value);}
    /** 失败只使用机器分类，避免供应商错误回显敏感内容。 */
    public void error(String code){span.setStatus(StatusCode.ERROR,code);span.setAttribute("langfuse.observation.level","ERROR");}
    /** 结束该次执行，等待用户时不持有开放span。 */
    public void close(){scope.close();span.end();}
  }
}
