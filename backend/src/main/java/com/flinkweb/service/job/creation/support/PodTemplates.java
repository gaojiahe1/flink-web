package com.flinkweb.service.job.creation.support;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.exception.common.BusinessException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 保留共享与角色 PodTemplate，并为平台托管字段提供具名数组合并。 */
@Component
public class PodTemplates {
  /** 仅检查三处模板的对象结构，将共享与角色覆盖的合并交给 Operator。 */
  public void validateStructure(ObjectNode spec) {
    for (String role : List.of("", "jobManager", "taskManager")) {
      JsonNode parent = role.isEmpty() ? spec : spec.path(role);
      JsonNode pod = parent.path("podTemplate");
      if (!pod.isMissingNode()
          && (!pod.isObject()
              || pod.has("spec") && !pod.path("spec").isObject()
              || pod.has("metadata") && !pod.path("metadata").isObject()))
        throw new BusinessException(
            HttpStatus.BAD_REQUEST,
            "INVALID_YAML",
            "podTemplate: "
                + (role.isEmpty() ? "共享" : role)
                + " PodTemplate 及其 spec/metadata 必须为对象");
      JsonNode podSpec = pod.path("spec");
      for (String field : List.of("containers", "initContainers", "volumes")) {
        if (!podSpec.has(field)) continue;
        if (!podSpec.path(field).isArray())
          throw new BusinessException(
              HttpStatus.BAD_REQUEST, "INVALID_YAML", "podTemplate: " + field + " 必须为数组");
        for (JsonNode item : podSpec.path(field))
          if (!item.isObject())
            throw new BusinessException(
                HttpStatus.BAD_REQUEST, "INVALID_YAML", "podTemplate: " + field + " 元素必须为对象");
      }
    }
  }

  /** 有角色覆盖时启用具名数组合并，避免下载器、边车或环境变量被按位置覆盖。 */
  public void configureManagedMerge(ObjectNode spec) {
    if (!spec.path("jobManager").has("podTemplate") && !spec.path("taskManager").has("podTemplate"))
      return;
    var config = spec.withObject("/flinkConfiguration");
    String key = "kubernetes.operator.pod-template.merge-arrays-by-name";
    if (config.has(key) && !"true".equals(config.path(key).asText()))
      throw new BusinessException(
          HttpStatus.BAD_REQUEST,
          "INVALID_YAML",
          "flinkConfiguration: 平台下载器与角色 PodTemplate 共用时需启用 " + key);
    config.put(key, "true");
  }

  /** 校验所有角色的主容器挂载，防止自定义角色覆盖平台管理的目录。 */
  public void checkManagedMount(ObjectNode spec, String path, String name) {
    for (String role : List.of("", "jobManager", "taskManager")) {
      JsonNode pod = (role.isEmpty() ? spec : spec.path(role)).path("podTemplate").path("spec");
      for (JsonNode container : pod.path("containers"))
        if ("flink-main-container".equals(container.path("name").asText()))
          for (JsonNode mount : container.path("volumeMounts"))
            if (path.equals(mount.path("mountPath").asText())
                && !name.equals(mount.path("name").asText()))
              throw new BusinessException(
                  HttpStatus.BAD_REQUEST, "INVALID_YAML", "podTemplate: 目录已有自定义挂载：" + path);
    }
  }

  /** 按 name 合并或新增数组元素，保留同名容器、环境变量或卷的其他配置。 */
  public void upsert(ObjectNode parent, String key, ObjectNode value) {
    ArrayNode list =
        parent.path(key).isArray() ? (ArrayNode) parent.get(key) : parent.putArray(key);
    for (int i = 0; i < list.size(); i++)
      if (list.get(i).path("name").equals(value.path("name"))) {
        list.set(i, merge((ObjectNode) list.get(i), value));
        return;
      }
    list.add(value);
  }

  /** 递归合并对象及具名数组，标量值由覆盖模板优先。 */
  public ObjectNode merge(ObjectNode target, JsonNode override) {
    if (!override.isObject()) return target;
    override
        .fields()
        .forEachRemaining(
            entry -> {
              String key = entry.getKey();
              JsonNode value = entry.getValue();
              if (value.isObject() && target.path(key).isObject())
                merge((ObjectNode) target.get(key), value);
              else if (value.isArray()
                  && target.path(key).isArray()
                  && value.size() > 0
                  && value.get(0).has("name"))
                for (JsonNode item : value) upsert(target, key, (ObjectNode) item);
              else target.set(key, value.deepCopy());
            });
    return target;
  }
}
