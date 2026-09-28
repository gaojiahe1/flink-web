package com.flinkweb.service.catalog;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 服务契约：维护模板、镜像、配置及制品目录，负责输入校验、密文存储和输出脱敏。 */
public interface CatalogService {

  /** 查询目录记录并对敏感配置脱敏。 */
  List<ObjectNode> list(String type);

  /** 读取指定目录记录并返回可展示的字段。 */
  ObjectNode get(String type, String id);

  /** 按目录类型校验字段，处理配置加密后以修订号保存并审计。 */
  ObjectNode save(String type, String id, ObjectNode input);

  /** 按修订号删除目录记录并保存审计结果。 */
  void delete(String type, String id, long revision);
}
