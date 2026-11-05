package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.client.ai.AiTelemetry;
import com.flinkweb.client.ai.SpringAiModelGateway;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.security.permission.SecretCipher;
import com.flinkweb.service.ai.support.AiContentGuard;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;

/** 正文导出的真实SDK与模型HTTP回归，覆盖脱敏、开关、大小预算和业务隔离。 */
class AiTelemetryContentTest {
  final ObjectMapper mapper=new ObjectMapper();
  final RecordRepository records=mock(RecordRepository.class);
  final SecretCipher cipher=mock(SecretCipher.class);
  final AiContentGuard guard=new AiContentGuard(records,cipher);
  final List<SpanData> spans=new CopyOnWriteArrayList<>();
  OpenTelemetrySdk sdk;
  AiTelemetry telemetry;

  /** 每例使用独立记录器，不连接外部Langfuse。 */
  @BeforeEach
  void setup(){
    sdk=OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(new Exporter(spans))).build()).build();
    telemetry=new AiTelemetry(sdk,mapper,guard::sanitizeTelemetry,true);
  }
  /** 完成所有span并释放本例SDK。 */
  @AfterEach
  void close(){sdk.close();}

  /** 标准Langfuse字段保留JSON结构并遮蔽嵌套/字符串参数中的凭据及推理。 */
  @Test
  void exportsSanitizedInputAndOutputWithoutMutatingBusinessData()throws Exception{
    when(records.list("ai_models")).thenReturn(List.of(mapper.createObjectNode().put("id","m").put("encryptedKey","encrypted")));
    when(cipher.decrypt("ai-model:m","encrypted")).thenReturn("registered-secret");
    var input=mapper.readTree("{\"sql\":\"SELECT 1\",\"nested\":{\"password\":\"unknown-password\",\"authorization\":\"Basic basic-credential\"},\"content\":\"registered-secret\",\"arguments\":\"{\\\"apiKey\\\":\\\"unknown-key\\\",\\\"table\\\":\\\"orders\\\",\\\"reasoning_content\\\":\\\"private-argument-reasoning\\\"}\",\"reasoning_content\":\"private-reasoning\",\"env\":[{\"name\":\"AWS_SECRET_ACCESS_KEY\",\"value\":\"unknown-env-secret\"}],\"passwordRef\":\"ref\"}");
    try(var trace=telemetry.start("tool.sql_check","tool",Map.of())){trace.input(input);trace.output(mapper.createObjectNode().put("content","检查完成").put("reasoning_content","private-output-reasoning"));}
    var span=spans.get(0);String text=span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input"));
    assertThat(text).contains("SELECT 1","orders","passwordRef","[REDACTED]").doesNotContain("unknown-password","basic-credential","registered-secret","unknown-key","private-argument-reasoning","private-reasoning","unknown-env-secret");
    assertThat(mapper.readTree(text).at("/nested/password").asText()).isEqualTo("[REDACTED]");
    assertThat(span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output"))).contains("检查完成").doesNotContain("private-output-reasoning");
    assertThat(input.at("/nested/password").asText()).isEqualTo("unknown-password");assertThat(input.has("reasoning_content")).isTrue();
  }

  /** 原始认证头、完整及残缺think块不外发，平台引用仍可识别。 */
  @Test
  void removesHeaderCredentialsAndReasoningBlocks(){
    var safe=guard.sanitizeTelemetry(mapper.createObjectNode().put("text","Authorization: Basic secret-basic\nCookie: session=secret-cookie\nBearer secret-bearer\n<think>private-thinking</think>可见回答").put("secret","{{secret:reference}}"));
    assertThat(safe.toString()).contains("可见回答","{{secret:reference}}").doesNotContain("secret-basic","secret-cookie","secret-bearer","private-thinking");
    assertThat(guard.sanitizeTelemetry(mapper.getNodeFactory().textNode("<think>unfinished-private" )).asText()).doesNotContain("unfinished-private");
    var quoted=guard.sanitizeTelemetry(mapper.getNodeFactory().textNode("password='private value with spaces' api_key=\"another private value\" master_key='master private words' password='{{secret:reference}}'"));
    assertThat(quoted.asText()).doesNotContain("private value","with spaces","another","master private words").contains("{{secret:reference}}");
  }

  /** 关闭正文开关或SDK未采样时，不执行脱敏查询，也不导出正文。 */
  @Test
  void contentSwitchAndDisabledSdkDoNotReadSecrets(){
    AiTelemetry.ContentSanitizer forbidden=value->{throw new AssertionError("Should not sanitize disabled content");};
    for(var t:List.of(new AiTelemetry(sdk,mapper,forbidden,false),new AiTelemetry(io.opentelemetry.api.OpenTelemetry.noop(),mapper,forbidden,true)))try(var trace=t.start("metadata-only","chain",Map.of())){trace.input(Map.of("message","private"));trace.output("private");}
    assertThat(spans).hasSize(1);assertThat(spans.get(0).getAttributes().get(AttributeKey.stringKey("langfuse.observation.input"))).isNull();verifyNoInteractions(records,cipher);
  }

  /** 超预算保留合法JSON及截断标记，先脱敏再截断且不拆开中文代理对。 */
  @Test
  void truncatesAfterSanitizationAndKeepsValidJson()throws Exception{
    var input=mapper.createObjectNode().put("message","中文😀\"".repeat(12000)).put("apiKey","sensitive-tail");
    try(var trace=telemetry.start("long","generation",Map.of())){trace.input(input);}
    String text=spans.get(0).getAttributes().get(AttributeKey.stringKey("langfuse.observation.input"));
    assertThat(text.length()).isLessThanOrEqualTo(32768);assertThat(text).doesNotContain("sensitive-tail");var parsed=mapper.readTree(text);assertThat(parsed.path("truncated").asBoolean()).isTrue();assertThat(parsed.path("originalCharacters").asInt()).isGreaterThan(32768);assertThat(parsed.path("preview").asText()).contains("中文😀");
  }

  /** 脱敏依赖失败时关闭该份正文，业务调用和span结算仍可完成。 */
  @Test
  void sanitizerFailureDoesNotAffectBusinessExecution(){
    var failing=new AiTelemetry(sdk,mapper,value->{throw new IllegalStateException("secret failure detail");},true);
    assertThatCode(()->{try(var trace=failing.start("failure","event",Map.of())){trace.input("private input");trace.output("private output");}}).doesNotThrowAnyException();
    assertThat(spans.get(0).getAttributes().get(AttributeKey.booleanKey("flink.telemetry.input.unavailable"))).isTrue();assertThat(spans.get(0).getAttributes().toString()).doesNotContain("private input","private output","secret failure detail");
  }

  /** 实际Spring AI流式兼容请求导出完整消息/最终回复，HTTP密钥与链内推理不进入观测。 */
  @Test
  void actualModelCallExportsMessagesAndFinalResponse()throws Exception{
    var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/chat",exchange->{exchange.getRequestBody().readAllBytes();byte[] response="{\"id\":\"qa\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"最终回答\",\"reasoning_content\":\"private-model-reasoning\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3,\"total_tokens\":15}}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});server.start();
    try{
      var messages=mapper.createArrayNode();messages.addObject().put("role","user").put("content","解释SELECT 1");var model=mapper.createObjectNode().put("model","test-model").put("timeoutSeconds",5);var streamed=new StringBuilder();
      var result=new SpringAiModelGateway(mapper,telemetry).call(model,"transport-only-secret",java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat"),messages,mapper.createArrayNode(),streamed::append);
      assertThat(result.path("content").asText()).isEqualTo("最终回答");assertThat(streamed.toString()).isEqualTo("最终回答");assertThat(spans).hasSize(1);
      assertThat(spans.get(0).getAttributes().get(AttributeKey.stringKey("langfuse.observation.input"))).contains("SELECT 1","messages");assertThat(spans.get(0).getAttributes().get(AttributeKey.stringKey("langfuse.observation.output"))).contains("最终回答");assertThat(spans.get(0).getAttributes().toString()).doesNotContain("transport-only-secret","private-model-reasoning");
    }finally{server.stop(0);}
  }

  /** 同步记录SDK完成的span，仅用于当前测试。 */
  private record Exporter(List<SpanData> spans) implements SpanExporter {
    /** 保存真实SDK数据供断言。 */
    public CompletableResultCode export(Collection<SpanData> values){spans.addAll(values);return CompletableResultCode.ofSuccess();}
    /** 同步导出无需刷新。 */
    public CompletableResultCode flush(){return CompletableResultCode.ofSuccess();}
    /** 不持有外部资源。 */
    public CompletableResultCode shutdown(){return CompletableResultCode.ofSuccess();}
  }
}
