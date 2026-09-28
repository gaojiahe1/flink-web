package com.flinkweb.sql;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.flink.table.api.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 使用真实 Flink Planner 验证 SQL Runner 计划构建，并检查参数和密钥替换。 */
class SqlRunnerTest {
    /** 两个 INSERT 进入同一 StatementSet，真实 Planner 能解释执行计划。 */
    @Test
    void plansMultipleInsertsWithRealPlanner() {
        TableEnvironment environment =
                TableEnvironment.create(EnvironmentSettings.inStreamingMode());
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
                            Base64.getEncoder()
                                    .encodeToString(source.getBytes(StandardCharsets.UTF_8))
                        }));
        assertThrows(
                IllegalArgumentException.class,
                () -> SqlRunner.decode(new String[] {"--bad", "a"}));
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
        TableEnvironment environment =
                TableEnvironment.create(EnvironmentSettings.inStreamingMode());
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
        StatementSet plan = SqlRunner.prepare(environment, List.of(
                "CREATE CATALOG analytics WITH ('type'='generic_in_memory', 'default-database'='warehouse')",
                "USE CATALOG analytics",
                "USE warehouse",
                "CREATE DATABASE archive_db",
                "CREATE TEMPORARY TABLE sink_table (n INT) WITH ('connector'='blackhole')",
                "INSERT INTO analytics.warehouse.sink_table VALUES (1)"), key -> null);
        assertEquals("analytics", environment.getCurrentCatalog());
        assertEquals("warehouse", environment.getCurrentDatabase());
        assertTrue(plan.explain().contains("sink_table"));
        assertThrows(IllegalArgumentException.class, () -> SqlRunner.prepare(environment,
                List.of("INSERT INTO sink_table VALUES (2)", "USE CATALOG default_catalog"), key -> null));
    }
}
