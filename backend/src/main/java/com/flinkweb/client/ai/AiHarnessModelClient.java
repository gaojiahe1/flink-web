package com.flinkweb.client.ai;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Harness的Spring AI适配入口；不执行工具、不持久化供应商内部推理。 */
@Component
public class AiHarnessModelClient {
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  private AiTelemetry telemetry=AiTelemetry.noop();
  private final ObjectMapper mapper;
  private final AiModelClient endpoints;
  /** 复用登记地址校验，模型框架不改变目标网络边界。 */
  public AiHarnessModelClient(ObjectMapper mapper,AiModelClient endpoints){this.mapper=mapper;this.endpoints=endpoints;}
  /** Spring AI处理流式协议，完整响应验证后才向Harness交付工具请求。 */
  public ObjectNode stream(ObjectNode model,String key,ArrayNode messages,ArrayNode tools,Consumer<String> text)throws Exception {
    return new SpringAiModelGateway(mapper,telemetry).call(model,key,endpoints.endpoint(model.path("baseUrl").asText(),model.path("privateNetwork").asBoolean()),messages,tools,text);
  }
}
