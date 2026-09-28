package com.flinkweb.service.job.creation;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.yaml.snakeyaml.*;

/** 服务契约：安全解析和校验 FlinkDeployment YAML，应用默认 Namespace、运行账号及状态存储策略。 */
public interface YamlService {
  /** 限制 YAML 大小、标签、重复键及别名，将单个文档安全转换为对象。 */
  ObjectNode parse(String source);

  /** 检查部署类型、Namespace、Flink 版本和作业参数，补充允许的默认配置。 */
  ObjectNode validate(ObjectNode node, String expectedName);

  /** 将 JSON 资源对象序列化为可编辑的 YAML。 */
  String dump(JsonNode node);

  /** 读取并验证指定字段为对象，缺少时按校验规则处理。 */
  ObjectNode object(JsonNode parent, String key);

  /** 抛出明确的 YAML 校验错误。 */
  void fail(String message);
}
