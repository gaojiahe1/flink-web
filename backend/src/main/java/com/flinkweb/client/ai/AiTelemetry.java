package com.flinkweb.client.ai;
import io.opentelemetry.api.*;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** AI专属观测边界：仅元数据、耗时和用量，不采集密钥、消息、日志或内部推理。 */
@Component
public class AiTelemetry {
  private static final ContextKey<Map<String,String>> ATTRIBUTES=ContextKey.named("flink-ai-trace");
  private final Tracer tracer;
  /** 注入可禁用的SDK，未配置Langfuse不影响应用启动。 */
  public AiTelemetry(OpenTelemetry telemetry){tracer=telemetry.getTracer("flink-web-ai","0.0.10");}
  /** 非Spring单元测试沿用无网络实现。 */
  public static AiTelemetry noop(){return new AiTelemetry(OpenTelemetry.noop());}
  /** 每个子观测复制会话、任务和账号属性，满足Langfuse v4的过滤要求。 */
  public Trace start(String name,String type,Map<String,String> metadata){
    var attrs=new HashMap<String,String>();var inherited=Context.current().get(ATTRIBUTES);if(inherited!=null)attrs.putAll(inherited);attrs.putAll(metadata);attrs.putIfAbsent("langfuse.trace.name",name);attrs.put("langfuse.release","0.0.10");
    var span=tracer.spanBuilder(name).startSpan();attrs.forEach(span::setAttribute);span.setAttribute("langfuse.observation.type",type);
    var context=Context.current().with(span).with(ATTRIBUTES,Map.copyOf(attrs));return new Trace(span,context.makeCurrent());
  }
  /** 一段受控执行的生命周期，错误只记录分类，禁止记录原始异常正文。 */
  public static final class Trace implements AutoCloseable {
    private final Span span;private final Scope scope;
    /** 保留对应当前线程的scope，必须由创建线程结算。 */
    private Trace(Span span,Scope scope){this.span=span;this.scope=scope;}
    /** 返回关联标识供页面定位，不接受用户伪造父轨迹。 */
    public String id(){return span.isRecording()&&span.getSpanContext().isValid()?span.getSpanContext().getTraceId():"";}
    /** 仅允许调用方传入已确定的元数据，不传原始模型输入输出。 */
    public void attribute(String name,String value){span.setAttribute(name,value);}
    /** 模型usage使用真实值，缺失时不补造。 */
    public void usage(String key,long value){span.setAttribute(key,value);}
    /** 失败只使用机器分类，避免供应商错误回显敏感内容。 */
    public void error(String code){span.setStatus(StatusCode.ERROR,code);span.setAttribute("langfuse.observation.level","ERROR");}
    /** 结束该次执行，等待用户时不持有开放span。 */
    public void close(){scope.close();span.end();}
  }
}
