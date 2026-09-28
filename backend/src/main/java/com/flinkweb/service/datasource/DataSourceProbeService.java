package com.flinkweb.service.datasource;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.util.*;
import org.apache.kafka.clients.admin.*;

/** 服务契约：仅在用户点击时探查已登记数据源；不执行用户 SQL，不读取业务数据行。 */
public interface DataSourceProbeService {
  /** 测试 JDBC/Kafka 协议可用性；MySQL 可加载指定表结构，Kafka 不猜测消息结构。 */
  ObjectNode probe(String id, boolean schema);

  /** 按用户操作分层查询 MySQL 库、表或字段，限制返回条数，不读取业务行。 */
  ObjectNode metadata(String id, String database, String table);
}
