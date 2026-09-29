package com.flinkweb.datasource.metadata;

import static org.assertj.core.api.Assertions.*;

import com.flinkweb.config.platform.PlatformProperties;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 验证本地元数据持久化、文件锁和数据库版本升级。 */
class LocalStorageTest {

  /** 验证 V2 数据库升级 V3 保留旧记录，全量 DDL 可重复执行。 */
  @Test
  void upgradesVersionTwoWithoutLosingMetadataAndAcceptsFullDdl() throws Exception {
    Path path = Files.createTempDirectory("flink-web-upgrade-").resolve("platform.db");
    var storage = new StorageConfiguration();
    var properties =
        new PlatformProperties(
            "test",
            new PlatformProperties.Storage("local", path.toString(), true, null, null, null),
            null);
    try (var lock = storage.localStoreLock(properties);
        var ds = storage.dataSource(properties, lock)) {
      var jdbc = new JdbcTemplate(ds);
      try (var connection = ds.getConnection()) {
        for (String file : java.util.List.of("V1__job_metadata.sql", "V2__platform_records.sql"))
          org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
              connection, new org.springframework.core.io.ClassPathResource("db/sqlite/" + file));
      }
      jdbc.update(
          "INSERT INTO platform_records(collection,id,payload,revision) VALUES"
              + " ('configs','legacy','{}',8)");
      jdbc.update(
          "INSERT INTO platform_records(collection,id,payload,revision) VALUES"
              + " ('operations','old-op',?,1)",
          "{\"id\":\"old-op\",\"jobId\":\"old-job\",\"createdAt\":\"2026-01-01T00:00:00Z\"}");
      new SchemaInitializer(jdbc, properties);
      new SchemaInitializer(jdbc, properties);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM platform_record_subjects WHERE subject_id='old-job'",
                  Integer.class))
          .isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT revision FROM platform_records WHERE id='legacy'", Long.class))
          .isEqualTo(8L);
      assertThat(jdbc.queryForObject("SELECT MAX(version) FROM platform_schema", Integer.class))
          .isEqualTo(4);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM job_bindings", Integer.class)).isZero();
      try (var connection = ds.getConnection()) {
        for (int i = 0; i < 2; i++)
          org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(
              connection,
              new org.springframework.core.io.ClassPathResource("db/sqlite/schema.sql"));
      }
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM platform_schema", Integer.class))
          .isEqualTo(4);
    }
  }

  /** 验证数据重启后保留、第二实例不能占用文件且禁用初始化时拒绝丢失的数据库。 */
  @Test
  void persistsAcrossReopenAndRejectsSecondInstance() throws Exception {
    Path directory = Files.createTempDirectory("flink-web-local-"),
        path = directory.resolve("platform.db");
    var storage = new StorageConfiguration();
    var properties =
        new PlatformProperties(
            "test",
            new PlatformProperties.Storage("local", path.toString(), true, null, null, null),
            null);
    try (var lock = storage.localStoreLock(properties);
        var dataSource = storage.dataSource(properties, lock)) {
      var jdbc = new JdbcTemplate(dataSource);
      new SchemaInitializer(jdbc, properties);
      jdbc.update(
          "INSERT INTO platform_records(collection,id,payload,revision) VALUES"
              + " ('test','persist','{}',1)");
      assertThatThrownBy(() -> storage.localStoreLock(properties))
          .isInstanceOf(java.nio.channels.OverlappingFileLockException.class);
    }
    var reopen =
        new PlatformProperties(
            "test",
            new PlatformProperties.Storage("local", path.toString(), false, null, null, null),
            null);
    try (var lock = storage.localStoreLock(reopen);
        var dataSource = storage.dataSource(reopen, lock)) {
      var jdbc = new JdbcTemplate(dataSource);
      new SchemaInitializer(jdbc, reopen);
      assertThat(
              jdbc.queryForObject(
                  "SELECT revision FROM platform_records WHERE collection='test'"
                      + " AND id='persist'",
                  Long.class))
          .isEqualTo(1L);
      assertThat(jdbc.queryForObject("SELECT MAX(version) FROM platform_schema", Integer.class))
          .isEqualTo(4);
    }
    var missing =
        new PlatformProperties(
            "test",
            new PlatformProperties.Storage(
                "local", directory.resolve("missing.db").toString(), false, null, null, null),
            null);
    assertThatThrownBy(() -> storage.localStoreLock(missing))
        .isInstanceOf(com.flinkweb.exception.platform.StartupConfigurationException.class)
        .hasMessageContaining("FLINK_WEB_STORAGE_INITIALIZE=false");
    assertThat(Files.exists(directory.resolve("missing.db"))).isFalse();
  }
}
