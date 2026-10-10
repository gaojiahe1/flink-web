package com.flinkweb.service.job.creation.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.exception.common.BusinessException;
import org.springframework.http.HttpStatus;

/** 区分已确认表单转换的作业和默认使用 YAML 的历史作业，不用资源归属标签推断表单兼容性。 */
public final class JobEditorPolicy {
  public static final String LABEL = "flink-web/editor";

  /** 禁止实例化纯策略工具。 */
  private JobEditorPolicy() {}

  /** 检查是否已经确认过表单转换，无标记历史作业需单独确认。 */
  public static boolean allowsForm(JsonNode source) {
    return "jar-v1".equals(source.path("metadata").path("labels").path(LABEL).asText());
  }

  /** 给新表单或 YAML 草稿设置编辑来源标签。 */
  public static void mark(ObjectNode source, boolean form) {
    source.withObject("/metadata/labels").put(LABEL, form ? "jar-v1" : "yaml");
  }

  /** 拒绝未经确认对历史配置执行表单重写。 */
  public static void requireForm(JsonNode source) {
    if (!allowsForm(source))
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "YAML_ONLY_JOB", "此作业来自既有资源，切换 JAR 表单前请确认可能改变原 YAML");
  }
}
