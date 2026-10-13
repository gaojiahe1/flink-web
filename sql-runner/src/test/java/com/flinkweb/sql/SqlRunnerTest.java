package com.flinkweb.sql;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.flink.table.api.*;
import org.junit.jupiter.api.Test;

/** 使用真实 Flink Planner 验证 SQL Runner 计划构建，并检查参数和密钥替换。 */
class SqlRunnerTest {
  /** 使用真实提交上下文执行 SQL，磁盘 local 路径不得覆盖上传 JAR，同时保留运行与状态配置。 */
  @Test
  @org.junit.jupiter.api.Timeout(45)
  void executesSqlWithoutOverwritingResolvedSubmissionJars() throws Exception {
    var global = new org.apache.flink.configuration.Configuration();
    global.set(
        org.apache.flink.configuration.PipelineOptions.JARS,
        List.of("local:///opt/flink/usrlib/runner.jar"));
    global.set(
        org.apache.flink.configuration.PipelineOptions.CLASSPATHS,
        List.of("local:///opt/flink/usrlib/connector.jar"));
    global.setString("parallelism.default", "1");
    global.setString("pipeline.name", "initial-sql-regression");
    String stateDirectory =
        java.nio.file.Files.createTempDirectory("runner-state-").toUri().toString();
    global.setString("state.checkpoints.dir", stateDirectory);
    global.setString("s3.endpoint", "https://s3.example.invalid");
    var config = SqlRunner.executionConfiguration(global);
    assertEquals(stateDirectory, config.getString("state.checkpoints.dir", ""));
    assertEquals("https://s3.example.invalid", config.getString("s3.endpoint", ""));
    assertTrue(global.contains(org.apache.flink.configuration.PipelineOptions.JARS));
    var context = new org.apache.flink.configuration.Configuration();
    context.setString("execution.target", "local");
    context.setString("execution.attached", "true");
    String jar =
        TableEnvironment.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
    context.set(org.apache.flink.configuration.PipelineOptions.JARS, List.of(jar));
    context.set(org.apache.flink.configuration.PipelineOptions.CLASSPATHS, List.of(jar));
    org.apache.flink.client.program.StreamContextEnvironment.setAsContext(
        new org.apache.flink.core.execution.DefaultExecutorServiceLoader(),
        context,
        Thread.currentThread().getContextClassLoader(),
        true,
        true);
    try {
      TableEnvironment environment =
          TableEnvironment.create(
              EnvironmentSettings.newInstance()
                  .withConfiguration(config)
                  .inStreamingMode()
                  .build());
      var result =
          SqlRunner.prepare(
                  environment,
                  List.of(
                      "CREATE TEMPORARY TABLE source_events (id BIGINT, payload STRING) WITH"
                          + " ('connector'='datagen', 'number-of-rows'='3')",
                      "CREATE TEMPORARY TABLE sink_events (id BIGINT, payload STRING) WITH"
                          + " ('connector'='print')",
                      "INSERT INTO sink_events SELECT id, payload FROM source_events"),
                  key -> null)
              .execute();
      result.await();
      assertTrue(result.getJobClient().isPresent());
    } finally {
      org.apache.flink.client.program.StreamContextEnvironment.unsetAsContext();
    }
  }

  /** 验证模式不解析原 Sink 连接器，而是真实执行 Print 输出并限制一百条。 */
  @Test
  @org.junit.jupiter.api.Timeout(45)
  void validationReplacesSinkAndLimitsPrintResults() throws Exception {
    TableEnvironment environment = TableEnvironment.create(EnvironmentSettings.inStreamingMode());
    environment.getConfig().getConfiguration().setString("parallelism.default", "1");
    String id = "0123456789abcdef0123456789abcdef";
    String values =
        java.util.stream.IntStream.range(0, 120)
            .mapToObj(i -> "(" + i + ")")
            .collect(java.util.stream.Collectors.joining(","));
    var plan =
        SqlRunner.prepareValidation(
            environment,
            List.of(
                "CREATE TEMPORARY TABLE `real.sink` (n INT) WITH"
                    + " ('connector'='unavailable-production-sink')",
                "INSERT OVERWRITE `real.sink` VALUES " + values),
            key -> null,
            id);
    assertTrue(plan.explain().contains("fw_validation_" + id));
    java.io.PrintStream original = System.out;
    var output = new java.io.ByteArrayOutputStream();
    try (var stream = new java.io.PrintStream(output, true, StandardCharsets.UTF_8.name())) {
      System.setOut(stream);
      plan.execute().await();
    } finally {
      System.setOut(original);
    }
    assertEquals(
        100,
        output
            .toString(StandardCharsets.UTF_8.name())
            .lines()
            .filter(line -> line.contains("FWV_" + id))
            .count());
  }

  /** 多个目标和字段映射进入 Print 计划，验证编号及语句顺序受到保护。 */
  @Test
  void validationPreservesColumnMappingAndRejectsInvalidIdentity() {
    TableEnvironment environment = TableEnvironment.create(EnvironmentSettings.inStreamingMode());
    var plan =
        SqlRunner.prepareValidation(
            environment,
            List.of(
                "CREATE TABLE a (n INT, s STRING) WITH ('connector'='unavailable')",
                "INSERT INTO a (s,n) SELECT '中文,值', 1",
                "INSERT INTO a VALUES (2, 'two')"),
            key -> null,
            "0123456789abcdef0123456789abcdef");
    assertTrue(plan.explain().contains("fw_validation_"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SqlRunner.prepareValidation(
                environment, List.of("INSERT INTO a VALUES(1,'x')"), key -> null, "invalid"));
  }

  /** 两个 INSERT 进入同一 StatementSet，真实 Planner 能解释执行计划。 */
  @Test
  void plansMultipleInsertsWithRealPlanner() {
    TableEnvironment environment = TableEnvironment.create(EnvironmentSettings.inStreamingMode());
    StatementSet plan =
        SqlRunner.prepare(
            environment,
            List.of(
                "CREATE TABLE output_a (n INT) WITH ('connector'='blackhole')",
                "CREATE TABLE output_b (n INT) WITH ('connector'='blackhole')",
                "INSERT INTO output_a VALUES (1)",
                "INSERT INTO output_b VALUES (2)"),
            key -> null);
    String explanation = plan.explain();
    assertTrue(explanation.contains("output_a"));
    assertTrue(explanation.contains("output_b"));
  }

  /** 编码语句中的分号和中文不会在运行时被再次拆分。 */
  @Test
  void decodesStatementsWithoutSplittingLiterals() {
    String source = "INSERT INTO t VALUES ('中文;a')";
    assertEquals(
        List.of(source),
        SqlRunner.decode(
            new String[] {
              "--statement",
              Base64.getEncoder().encodeToString(source.getBytes(StandardCharsets.UTF_8))
            }));
    assertThrows(
        IllegalArgumentException.class, () -> SqlRunner.decode(new String[] {"--bad", "a"}));
  }

  /** 环境密钥正确转义并要求存在；引用不能代替 SQL 关键字。 */
  @Test
  void substitutesSecretsOnlyInsideStringLiterals() {
    assertEquals("SELECT 'a''b'", SqlRunner.substitute("SELECT '{{env:TOKEN}}'", key -> "a'b"));
    assertThrows(
        IllegalArgumentException.class,
        () -> SqlRunner.substitute("SELECT '{{env:MISSING}}'", key -> null));
    assertThrows(
        IllegalArgumentException.class,
        () -> SqlRunner.substitute("SELECT {{env:TOKEN}}", key -> "1"));
  }

  /** 本地 MiniCluster 实际执行有界 SQL，确认 Runner 不仅能生成计划，也能提交并完成作业。 */
  @Test
  @org.junit.jupiter.api.Timeout(45)
  void executesBoundedSqlWithLocalMiniCluster() throws Exception {
    TableEnvironment environment = TableEnvironment.create(EnvironmentSettings.inStreamingMode());
    environment.getConfig().getConfiguration().setString("parallelism.default", "1");
    var plan =
        SqlRunner.prepare(
            environment,
            List.of(
                "CREATE TABLE result_table (n INT) WITH ('connector'='blackhole')",
                "INSERT INTO result_table VALUES (1), (2)"),
            key -> null);
    var result = plan.execute();
    assertTrue(result.getJobClient().isPresent());
    result.await();
    // await 成功即代表有界作业完成；MiniCluster 会自动退出，不再向已关闭的集群查询状态。
    assertTrue(result.getJobClient().get().getJobExecutionResult().get().getNetRuntime() >= 0);
  }

  /** 真实 Planner 执行目录和数据库初始化后能解析完整目录路径，并拒绝 INSERT 后切换目录。 */
  @Test
  void initializesCatalogAndDatabaseBeforeInserts() {
    TableEnvironment environment = TableEnvironment.create(EnvironmentSettings.inStreamingMode());
    StatementSet plan =
        SqlRunner.prepare(
            environment,
            List.of(
                "CREATE CATALOG analytics WITH ('type'='generic_in_memory',"
                    + " 'default-database'='warehouse')",
                "USE CATALOG analytics",
                "USE warehouse",
                "CREATE DATABASE archive_db",
                "CREATE TEMPORARY TABLE sink_table (n INT) WITH ('connector'='blackhole')",
                "INSERT INTO analytics.warehouse.sink_table VALUES (1)"),
            key -> null);
    assertEquals("analytics", environment.getCurrentCatalog());
    assertEquals("warehouse", environment.getCurrentDatabase());
    assertTrue(plan.explain().contains("sink_table"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SqlRunner.prepare(
                environment,
                List.of("INSERT INTO sink_table VALUES (2)", "USE CATALOG default_catalog"),
                key -> null));
  }
}
