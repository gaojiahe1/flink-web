package com.flinkweb;
import static org.assertj.core.api.Assertions.*;
import com.flinkweb.client.ai.AiTelemetry;
import com.flinkweb.config.ai.AiTelemetryConfiguration;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.*;
import io.opentelemetry.sdk.trace.export.*;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** SDK父子关系、脱敏边界及两种OTLP路由的真实HTTP验证。 */
class AiTelemetryIntegrationTest {
  /** 所有子观测保留会话属性，错误仅记录分类，不包含模型正文。 */
  @Test
  void propagatesMetadataAndParentage(){
    var spans=new java.util.concurrent.CopyOnWriteArrayList<SpanData>();
    SpanExporter exporter=new SpanExporter(){
      /** 测试导出器只保存真实SDK产物。 */
      public CompletableResultCode export(Collection<SpanData> values){spans.addAll(values);return CompletableResultCode.ofSuccess();}
      /** 同步测试无需额外刷新。 */
      public CompletableResultCode flush(){return CompletableResultCode.ofSuccess();}
      /** 释放导出器。 */
      public CompletableResultCode shutdown(){return CompletableResultCode.ofSuccess();}
    };
    try(var sdk=OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()).build()){
      var telemetry=new AiTelemetry(sdk);String traceId;
      try(var root=telemetry.start("harness.turn","chain",Map.of("langfuse.session.id","session-qa","langfuse.user.id","user-qa"))){traceId=root.id();try(var generation=telemetry.start("model","generation",Map.of("gen_ai.request.model","test-model"))){generation.usage("gen_ai.usage.input_tokens",12);}try(var tool=telemetry.start("tool.sql_check","tool",Map.of())){tool.error("SQL_CHECK_FAILED");}}
      assertThat(spans).hasSize(3);for(var span:spans){assertThat(span.getTraceId()).isEqualTo(traceId);assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("langfuse.session.id"))).isEqualTo("session-qa");assertThat(span.getAttributes().asMap().keySet().stream().map(Object::toString)).noneMatch(k->k.contains("input")&&!k.contains("tokens")||k.contains("output")&&!k.contains("tokens"));}
      var root=spans.stream().filter(s->s.getName().equals("harness.turn")).findFirst().orElseThrow();assertThat(spans.stream().filter(s->!s.getName().equals("harness.turn"))).allSatisfy(s->assertThat(s.getParentSpanId()).isEqualTo(root.getSpanId()));
    }
  }
  /** 直连发送项目Basic认证，Collector模式不携带Langfuse密钥，均为OTLP protobuf。 */
  @Test
  void directAndCollectorUseDifferentCredentialBoundaries()throws Exception{
    var paths=new java.util.concurrent.CopyOnWriteArrayList<String>();var auth=new java.util.concurrent.CopyOnWriteArrayList<String>();var content=new java.util.concurrent.CopyOnWriteArrayList<String>();
    var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{paths.add(exchange.getRequestURI().getPath());auth.add(exchange.getRequestHeaders().getFirst("Authorization")==null?"":exchange.getRequestHeaders().getFirst("Authorization"));content.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.ISO_8859_1));exchange.sendResponseHeaders(200,0);exchange.close();});server.start();
    try{String base="http://127.0.0.1:"+server.getAddress().getPort();var config=new AiTelemetryConfiguration();
      for(String mode:List.of("direct","collector"))try(var sdk=config.aiOpenTelemetry(true,base+"/v1/traces",mode,base,"public-test","secret-test")){emit(sdk,"transport-"+mode);assertThat(sdk.getSdkTracerProvider().forceFlush().join(10,TimeUnit.SECONDS).isSuccess()).isTrue();}
      assertThat(paths).containsExactly("/api/public/otel/v1/traces","/v1/traces");assertThat(auth.get(0)).startsWith("Basic ");assertThat(auth.get(1)).isEmpty();assertThat(content).allSatisfy(body->assertThat(body).contains("harness.turn","langfuse.session.id").doesNotContain("secret-test","Authorization"));
    }finally{server.stop(0);}
  }
  /** 关闭时不要求凭据；开启直连必须提供有效配置。 */
  @Test
  void validatesModesWithoutBreakingDisabledStartup(){var config=new AiTelemetryConfiguration();try(var sdk=config.aiOpenTelemetry(false,"invalid","direct","invalid","","")){try(var trace=new AiTelemetry(sdk).start("test","span",Map.of())){assertThat(trace.id()).isEmpty();}}assertThatThrownBy(()->config.aiOpenTelemetry(true,"http://localhost:4318/v1/traces","direct","http://localhost:3000","","")).hasMessageContaining("Key");assertThatThrownBy(()->config.aiOpenTelemetry(true,"http://localhost:4318/v1/traces","invalid","","","")).hasMessageContaining("mode");}
  /** 显式现场环境才发送合成验证轨迹，不发送项目SQL/日志或生产用户信息。 */
  @Test
  @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="FLINK_TEST_LIVE_LANGFUSE",matches="true")
  void liveLangfuse()throws Exception{String mode=System.getenv().getOrDefault("FLINK_WEB_AI_TELEMETRY_MODE","direct");try(var sdk=new AiTelemetryConfiguration().aiOpenTelemetry(true,System.getenv().getOrDefault("FLINK_WEB_OTEL_TRACES_ENDPOINT","http://127.0.0.1:14318/v1/traces"),mode,System.getenv().getOrDefault("LANGFUSE_BASE_URL","http://127.0.0.1:3000"),System.getenv("LANGFUSE_PUBLIC_KEY"),System.getenv("LANGFUSE_SECRET_KEY"))){String id=emit(sdk,"flink-web-integration-"+mode);assertThat(sdk.getSdkTracerProvider().forceFlush().join(15,TimeUnit.SECONDS).isSuccess()).isTrue();System.out.println("LANGFUSE_QA_TRACE "+mode+" "+id);}}
  /** 生成可核对的模型/工具层级及真实usage字段。 */
  private String emit(OpenTelemetrySdk sdk,String session){var telemetry=new AiTelemetry(sdk);try(var root=telemetry.start("harness.turn","chain",Map.of("langfuse.session.id",session,"langfuse.user.id","integration-test"))){try(var model=telemetry.start("ai.generation","generation",Map.of("gen_ai.request.model","test-model"))){model.usage("gen_ai.usage.input_tokens",12);model.usage("gen_ai.usage.output_tokens",3);}try(var tool=telemetry.start("tool.sql_check","tool",Map.of())){tool.attribute("flink.tool.name","sql_check");}return root.id();}}
}
