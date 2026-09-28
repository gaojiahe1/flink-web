package com.flinkweb.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.datasource.DataSourceService;
import com.flinkweb.service.datasource.impl.DataSourceServiceImpl;
import org.junit.jupiter.api.Test;

/** 验证数据源 SQL 生成、密码引用与输入边界，测试不连接外部数据源。 */
class DataSourceServiceTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final RecordRepository records = mock(RecordRepository.class);
  private final DataSourceService service = new DataSourceServiceImpl(records);

  /** 创建只包含基础字段和一列的数据源样例。 */
  private ObjectNode source(String type) {
    ObjectNode node =
        mapper.createObjectNode().put("connectorType", type).put("tableName", "events");
    node.putArray("columns").addObject().put("name", "id").put("type", "BIGINT");
    return node;
  }

  /** 验证 Kafka Topic 名称规则、有效建表语句及重复字段边界。 */
  @Test
  void kafkaEscapesOptionsAndRejectsDuplicateColumns() {
    ObjectNode node =
        source("kafka")
            .put("brokers", "kafka:9092")
            .put("topic", "orders'archive")
            .put("groupId", "group")
            .put("format", "json");
    assertThatThrownBy(() -> service.validate(node))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("Topic");
    node.put("topic", "orders-archive");
    when(records.require("datasources", "events")).thenReturn(node);
    assertThat(service.snippet("events").path("sql").asText())
        .contains("orders-archive", "'connector' = 'kafka'", "CREATE TEMPORARY TABLE");
    node.withArray("columns").addObject().put("name", "ID").put("type", "STRING");
    assertThatThrownBy(() -> service.validate(node)).isInstanceOf(BusinessException.class);
  }

  /** 验证 JDBC 密码只生成环境变量引用，普通配置不能作为密码使用。 */
  @Test
  void mysqlReferencesSecretWithoutReturningItsValue() {
    ObjectNode node =
        source("mysql")
            .put("host", "mysql.internal")
            .put("port", "3306")
            .put("database", "orders")
            .put("table", "events")
            .put("username", "reader")
            .put("passwordRef", "mysql-password");
    when(records.require("configs", "mysql-password"))
        .thenReturn(
            mapper.createObjectNode().put("kind", "secret").put("encryptedValue", "never-return"));
    when(records.require("datasources", "mysql")).thenReturn(node);
    var snippet = service.snippet("mysql");
    assertThat(snippet.toString())
        .doesNotContain("never-return")
        .contains(
            "{{env:DS_", "{{secret:mysql-password}}", "jdbc:mysql://mysql.internal:3306/orders");
    when(records.require("configs", "mysql-password"))
        .thenReturn(mapper.createObjectNode().put("kind", "plain"));
    assertThatThrownBy(() -> service.validate(node)).isInstanceOf(BusinessException.class);
  }

  /** 验证字段类型、模板注入和非法连接器在登记时被拒绝。 */
  @Test
  void rejectsInjectedTypesVariablesAndUnsupportedConnector() {
    ObjectNode node =
        source("kafka")
            .put("brokers", "kafka:9092")
            .put("topic", "{{env:PASSWORD}}")
            .put("groupId", "group")
            .put("format", "json");
    assertThatThrownBy(() -> service.validate(node)).isInstanceOf(BusinessException.class);
    node.put("topic", "orders");
    ((ObjectNode) node.path("columns").get(0)).put("type", "STRING); DROP TABLE t;");
    assertThatThrownBy(() -> service.validate(node)).isInstanceOf(BusinessException.class);
    node.put("connectorType", "unsupported");
    assertThatThrownBy(() -> service.validate(node)).isInstanceOf(BusinessException.class);
  }
}
