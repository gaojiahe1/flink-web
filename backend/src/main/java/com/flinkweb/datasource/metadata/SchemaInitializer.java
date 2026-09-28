package com.flinkweb.datasource.metadata;

import com.flinkweb.config.platform.PlatformProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 检查本地数据库完整性，并按顺序执行对应数据库的版本化 DDL。 */
public final class SchemaInitializer {
  private static final String[] MIGRATIONS = {
    "V1__job_metadata.sql",
    "V2__platform_records.sql",
    "V3__job_bindings.sql",
    "V4__record_subject_index.sql"
  };

  /** 检查已有模式版本与本地数据库完整性，依次应用缺失的 DDL 迁移。 */
  public SchemaInitializer(JdbcTemplate jdbc, PlatformProperties properties) {
    String dialect = "local".equals(properties.storage().type()) ? "sqlite" : "mysql";
    if (dialect.equals("sqlite")
        && !"ok".equals(jdbc.queryForObject("PRAGMA quick_check", String.class)))
      throw new IllegalStateException("本地元数据库完整性校验失败");
    jdbc.execute("CREATE TABLE IF NOT EXISTS platform_schema (version INTEGER PRIMARY KEY)");
    var versions = jdbc.queryForList("SELECT version FROM platform_schema", Integer.class);
    if (versions.stream().anyMatch(v -> v < 1 || v > MIGRATIONS.length))
      throw new IllegalStateException("数据库包含当前程序不支持的迁移版本");
    for (int i = 0; i < MIGRATIONS.length; i++)
      if (!versions.contains(i + 1)) apply(jdbc, dialect, MIGRATIONS[i]);
    backfillSubjects(jdbc);
  }

  /** 为升级前已有历史补齐主体索引；重复启动只处理缺失的记录。 */
  private void backfillSubjects(JdbcTemplate jdbc) {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var rows =
        jdbc.queryForList(
            "SELECT r.collection,r.id,r.payload FROM platform_records r LEFT JOIN"
                + " platform_record_subjects s ON r.collection=s.collection AND r.id=s.record_id"
                + " WHERE s.record_id IS NULL AND r.collection IN"
                + " ('releases','snapshots','operations','sql_versions')");
    for (var row : rows) {
      try {
        var node = mapper.readTree((String) row.get("payload"));
        String collection = (String) row.get("collection");
        String subject = node.path(collection.equals("sql_versions") ? "sqlId" : "jobId").asText();
        if (!subject.isBlank())
          jdbc.update(
              "INSERT INTO platform_record_subjects(collection,record_id,subject_id,created_at)"
                  + " VALUES (?,?,?,?)",
              collection,
              row.get("id"),
              subject,
              node.path("createdAt").asText(""));
      } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
        throw new IllegalStateException("历史元数据无法解析", ex);
      }
    }
  }

  /** 读取数据库类型对应的迁移 SQL，执行结构变更并记录版本。 */
  private void apply(JdbcTemplate jdbc, String dialect, String migration) {
    jdbc.execute(
        (ConnectionCallback<Void>)
            connection -> {
              ScriptUtils.executeSqlScript(
                  connection, new ClassPathResource("db/" + dialect + "/" + migration));
              return null;
            });
  }
}
