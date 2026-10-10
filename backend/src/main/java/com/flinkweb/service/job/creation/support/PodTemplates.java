package com.flinkweb.service.job.creation.support;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.exception.common.BusinessException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 维护共享 PodTemplate 和具名数组合并，避免生成重复角色模板。 */
@Component
public class PodTemplates {
  /** 将相同的历史角色模板折叠为公共模板；不同角色配置不能无损合并时明确拒绝。 */
  public void normalize(ObjectNode spec) {
    JsonNode common = spec.path("podTemplate");
    JsonNode jm = spec.path("jobManager").path("podTemplate");
    JsonNode tm = spec.path("taskManager").path("podTemplate");
    if (jm.isMissingNode() && tm.isMissingNode()) return;
    ObjectNode base =
        common.isObject()
            ? ((ObjectNode) common).deepCopy()
            : JsonNodeFactory.instance.objectNode();
    ObjectNode left = merge(base.deepCopy(), jm), right = merge(base.deepCopy(), tm);
    if (!left.equals(right))
      throw new BusinessException(
          HttpStatus.BAD_REQUEST,
          "POD_TEMPLATE_CONFLICT",
          "jobManager/taskManager 的 PodTemplate 不同，无法无损合并；请在 YAML 中整理为共享 podTemplate");
    if (!left.isEmpty()) spec.set("podTemplate", left);
    for (String role : List.of("jobManager", "taskManager"))
      if (spec.path(role).isObject()) ((ObjectNode) spec.get(role)).remove("podTemplate");
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
