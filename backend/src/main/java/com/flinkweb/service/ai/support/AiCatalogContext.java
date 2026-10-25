package com.flinkweb.service.ai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import java.util.*;
import org.springframework.http.HttpStatus;

/** 为两类 AI 会话投影已选择目录，只读取登记信息，不自动连接远端或发送凭据。 */
public final class AiCatalogContext {
  /** 静态工具不创建实例。 */
  private AiCatalogContext() {}

  /** 主目录和附加目录去重，返回语义及修订信息，未登记的引用保留不可用标识。 */
  public static ArrayNode collect(RecordRepository records, ObjectMapper mapper, JsonNode runtime) {
    var values = runtime.path("catalogIds");
    if (!values.isMissingNode() && (!values.isArray() || values.size() > 8))
      throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_AI_REQUEST", "最多选择8个Catalog");
    Set<String> ids = new LinkedHashSet<>();
    for (var item : values) ids.add(item.asText());
    if (!runtime.path("catalogId").asText().isBlank()) ids.add(runtime.path("catalogId").asText());
    if (ids.size() > 8)
      throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_AI_REQUEST", "最多选择8个Catalog");
    ArrayNode result = mapper.createArrayNode();
    for (String id : ids) {
      var catalog = records.find("flink_catalogs", id);
      var item = result.addObject().put("id", id);
      if (catalog == null) {
        item.put("unavailable", true);
        continue;
      }
      for (String field : List.of("name", "type", "defaultDatabase", "hiveVersion", "revision"))
        if (catalog.has(field)) item.set(field, catalog.get(field));
      item.put("primary", id.equals(runtime.path("catalogId").asText()));
    }
    return result;
  }
}
