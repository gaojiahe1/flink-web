package com.flinkweb.client.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.*;
import org.springframework.stereotype.Component;

/** 调用管理员登记的 Chat Completions 服务；限制目标、重定向、传输量和完整请求时长。 */
@Component
public class AiModelClient {
  @org.springframework.beans.factory.annotation.Autowired(required=false)
  private AiTelemetry telemetry=AiTelemetry.noop();
  private final ObjectMapper mapper;
  /** 注入 JSON 编解码器，不持有业务记录或用户身份。 */
  public AiModelClient(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  /** 校验 Base URL；内网模型必须显式允许，链路本地和元数据地址始终拒绝。 */
  public URI endpoint(String baseUrl, boolean privateNetwork) {
    try {
      URI base = URI.create(baseUrl);
      if (base.getHost() == null
          || base.getUserInfo() != null
          || base.getQuery() != null
          || base.getFragment() != null
          || base.getPort() == 0
          || base.getPort() > 65535
          || !("https".equals(base.getScheme())
              || privateNetwork && "http".equals(base.getScheme()))
          || base.getRawPath().contains("%")
          || base.getPath().contains("..")) throw new IllegalArgumentException();
      for (InetAddress address : InetAddress.getAllByName(base.getHost())) {
        byte[] bytes = address.getAddress();
        boolean uniqueLocal = bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        if (address.isAnyLocalAddress()
            || address.isLinkLocalAddress()
            || address.isMulticastAddress()
            || !privateNetwork
                && (address.isLoopbackAddress() || address.isSiteLocalAddress() || uniqueLocal))
          throw new IllegalArgumentException();
      }
      String prefix = base.toString().replaceAll("/+$", "");
      if ("api-inference.modelscope.cn".equalsIgnoreCase(base.getHost())
          && base.getPath().replace("/", "").isEmpty()) prefix += "/v1";
      return URI.create(prefix + "/chat/completions");
    } catch (Exception error) {
      throw new IllegalArgumentException("模型地址无效或目标网络未获允许");
    }
  }

  /** 所有模型调用通过Spring AI；保留旧助手的JSON对象契约。 */
  public ObjectNode complete(ObjectNode model,String key,String system,String context)throws Exception {
    var messages=mapper.createArrayNode();messages.addObject().put("role","system").put("content",system);messages.addObject().put("role","user").put("content",context);
    var response=new SpringAiModelGateway(mapper,telemetry).call(model,key,endpoint(model.path("baseUrl").asText(),model.path("privateNetwork").asBoolean()),messages,mapper.createArrayNode(),null);
    String text=response.path("content").asText().strip();if(text.startsWith("```"))text=text.replaceFirst("^```(?:json)?\\s*","").replaceFirst("\\s*```$","");
    var parsed=mapper.readTree(text);if(!(parsed instanceof ObjectNode result))throw new IllegalStateException("模型未返回约定的JSON对象");return result;
  }
}
