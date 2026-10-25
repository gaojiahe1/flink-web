package com.flinkweb.service.sql.catalog;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** Flink 元数据目录契约，与平台模板/制品目录分离，提供登记、初始化和按需浏览。 */
public interface FlinkCatalogService {
  /** 手动测试目录连接或初始化配置，返回准确的校验层级。 */
  ObjectNode test(String id);

  /** 查询已登记目录，不连接远程服务。 */
  List<ObjectNode> list();

  /** 校验并以修订号保存目录定义，不创建远程数据库。 */
  ObjectNode save(String id, ObjectNode input);

  /** 删除未被 SQL 草稿引用的目录登记。 */
  void delete(String id, long revision);

  /** 返回不含明文密码的目录初始化 SQL 和环境变量引用。 */
  ObjectNode snippet(String id, String database);

  /** 将目录密钥引用合入运行参数，并返回初始化 SQL 前缀。 */
  String prepare(ObjectNode runtime);

  /** 仅按用户操作读取 JDBC 目录的库、表或字段元数据。 */
  ObjectNode metadata(String id, String database, String table);

  /** 显式浏览目录函数；不支持的目录返回能力说明，不能冒充空查询结果。 */
  ObjectNode functions(String id, String database, String name);

  /** 返回当前用户可读且引用此目录的草稿，删除保护仍核对全部引用。 */
  ObjectNode usage(String id);
}
