package com.flinkweb.config.ai;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributeKey;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import java.net.URI;
import java.time.Duration;

/** AI观测支持直连Langfuse或经Collector转发；关闭时使用无网络的空实现。 */
@Configuration
public class AiTelemetryConfiguration {
  /** 将现有密钥脱敏能力桥接给观测客户端，保持分层且不建立长期明文缓存。 */
  @Bean
  public com.flinkweb.client.ai.AiTelemetry.ContentSanitizer aiTelemetryContentSanitizer(com.flinkweb.service.ai.support.AiContentGuard guard){return guard::sanitizeTelemetry;}
  /** 独立SDK不污染全局代理；队列有界，失败不阻断模型或业务提交。 */
  @Bean(destroyMethod="close")
  public OpenTelemetrySdk aiOpenTelemetry(@Value("${flink-web.ai.telemetry.enabled:false}") boolean enabled,
      @Value("${flink-web.ai.telemetry.endpoint:http://127.0.0.1:4318/v1/traces}") String endpoint,
      @Value("${flink-web.ai.telemetry.mode:collector}") String mode,
      @Value("${flink-web.ai.telemetry.langfuse-base-url:http://127.0.0.1:3000}") String langfuseUrl,
      @Value("${flink-web.ai.telemetry.public-key:}") String publicKey,
      @Value("${flink-web.ai.telemetry.secret-key:}") String secretKey,
      @Value("${flink-web.ai.telemetry.environment:default}") String environment){
    var provider=SdkTracerProvider.builder();
    if(enabled){
      if(environment==null||!environment.matches("^(?!langfuse)[a-z0-9_-]{1,40}$"))throw new IllegalArgumentException("Langfuse环境名需为1到40个小写字母、数字、下划线或连字符，且不能以langfuse开头");
      if(!java.util.Set.of("collector","direct").contains(mode))throw new IllegalArgumentException("AI观测mode只支持collector/direct");
      if(mode.equals("direct")){if(publicKey.isBlank()||secretKey.isBlank())throw new IllegalArgumentException("直连Langfuse需要项目Public/Secret Key");endpoint=langfuseUrl.replaceAll("/+$","")+"/api/public/otel/v1/traces";}
      var uri=URI.create(endpoint);if(!java.util.Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("OTLP Collector地址无效");
      var builder=OtlpHttpSpanExporter.builder().setEndpoint(endpoint).setTimeout(Duration.ofSeconds(3));
      if(mode.equals("direct")){builder.addHeader("Authorization","Basic "+java.util.Base64.getEncoder().encodeToString((publicKey+":"+secretKey).getBytes(java.nio.charset.StandardCharsets.UTF_8)));builder.addHeader("x-langfuse-ingestion-version","4");}
      var exporter=builder.build();
      provider.addSpanProcessor(BatchSpanProcessor.builder(exporter).setMaxQueueSize(2048).setMaxExportBatchSize(128).setScheduleDelay(Duration.ofSeconds(1)).build());
    }else provider.setSampler(io.opentelemetry.sdk.trace.samplers.Sampler.alwaysOff());
    provider.setResource(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"),"flink-web",AttributeKey.stringKey("service.version"),"0.0.10",AttributeKey.stringKey("langfuse.environment"),enabled?environment:"default")));
    return OpenTelemetrySdk.builder().setTracerProvider(provider.build()).build();
  }
}
