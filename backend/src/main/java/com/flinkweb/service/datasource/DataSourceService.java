package com.flinkweb.service.datasource;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 服务契约：管理基础 Kafka/JDBC MySQL 数据源定义，生成不含明文密钥的 Flink SQL 建表片段。 */
public interface DataSourceService {
  /** 校验连接参数、字段定义和密码引用，并仅保存允许的字段。 */
  ObjectNode validate(ObjectNode input);

  /** 为指定数据源生成 CREATE TABLE 和必要的 Pod 环境变量，连接只在 Flink 运行时建立。 */
  ObjectNode snippet(String id);

  /** 为当前作业的临时数据定义编译 DDL，不登记共享数据源。 */
  ObjectNode snippetDefinition(String id, ObjectNode input);
}
