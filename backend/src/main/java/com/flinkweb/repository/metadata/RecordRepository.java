package com.flinkweb.repository.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.exception.common.BusinessException;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.DependsOn;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 维护用户、模板、镜像、配置、制品、操作和审计等 JSON 元数据记录。 */
@Repository
@DependsOn("schemaInitializer")
public class RecordRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  /** 注入 JDBC 模板及 JSON 映射器。 */
  public RecordRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    // 表结构统一由 datasource 层的有序迁移维护。
  }

  /** 将数据库 JSON 内容转换为记录对象并恢复标识和修订号。 */
  private ObjectNode read(String json, long revision) {
    try {
      ObjectNode node = (ObjectNode) mapper.readTree(json);
      node.put("revision", revision);
      return node;
    } catch (Exception ex) {
      throw new IllegalStateException("Invalid persisted record", ex);
    }
  }

  /** 查询指定集合的全部元数据记录。 */
  public List<ObjectNode> list(String collection) {
    return jdbc.query(
        "SELECT payload,revision FROM platform_records WHERE collection=? ORDER BY id",
        (rs, row) -> read(rs.getString(1), rs.getLong(2)),
        collection);
  }

  /** 使用普通列索引读取某个作业的历史；排序稳定，不依赖 JSON 方言。 */
  @org.springframework.transaction.annotation.Transactional
  public List<ObjectNode> listForSubject(String collection, String subject) {
    pruneHistory(collection, subject);
    var result = jdbc.query(
        "SELECT r.payload,r.revision FROM platform_record_subjects s JOIN platform_records r ON"
            + " r.collection=s.collection AND r.id=s.record_id WHERE s.collection=? AND"
            + " s.subject_id=? ORDER BY s.created_at DESC,s.record_id DESC",
        (rs, row) -> read(rs.getString(1), rs.getLong(2)),
        collection,
        subject);
    if (java.util.Set.of("releases", "sql_versions").contains(collection))
      return result.stream().sorted(java.util.Comparator.comparing((ObjectNode n) -> Instant.parse(n.path("createdAt").asText())).thenComparing(n -> n.path("id").asText()).reversed()).toList();
    return result;
  }

  /** 查找指定记录，不存在时返回空值。 */
  public ObjectNode find(String collection, String id) {
    var rows =
        jdbc.query(
            "SELECT payload,revision FROM platform_records WHERE collection=? AND id=?",
            (rs, row) -> read(rs.getString(1), rs.getLong(2)),
            collection,
            id);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** 查找指定记录，不存在时抛出业务错误。 */
  public ObjectNode require(String collection, String id) {
    ObjectNode node = find(collection, id);
    if (node == null) throw new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", "记录不存在");
    return node;
  }

  /** 新增或按旧修订号更新 JSON 元数据，返回新修订号。 */
  @org.springframework.transaction.annotation.Transactional
  public ObjectNode save(String collection, String id, ObjectNode input, long expected) {
    if (expected > 0 && java.util.Set.of("releases", "sql_versions").contains(collection))
      throw new BusinessException(HttpStatus.CONFLICT, "IMMUTABLE_RECORD", "历史版本不能覆盖，请创建新版本");
    ObjectNode node = input.deepCopy();
    if (expected > 0) {
      ObjectNode current = find(collection, id);
      if (current != null && current.has("createdAt"))
        node.set("createdAt", current.get("createdAt"));
    }
    node.put("id", id);
    node.put("updatedAt", Instant.now().toString());
    node.remove("revision");
    try {
      if (expected == 0) {
        node.put("createdAt", Instant.now().toString());
        jdbc.update(
            "INSERT INTO platform_records(collection,id,payload,revision) VALUES" + " (?,?,?,1)",
            collection,
            id,
            node.toString());
      } else if (jdbc.update(
              "UPDATE platform_records SET payload=?,revision=revision+1 WHERE"
                  + " collection=? AND id=? AND revision=?",
              node.toString(),
              collection,
              id,
              expected)
          != 1) conflict();
    } catch (org.springframework.dao.DataAccessException ex) {
      if (ex instanceof org.springframework.dao.DuplicateKeyException) conflict();
      // SQLite 的扩展唯一键错误可能没有被 Spring 翻译成 DuplicateKeyException。
      for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
        if (cause instanceof org.sqlite.SQLiteException sqlite
            && (sqlite.getResultCode() == org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY
                || sqlite.getResultCode() == org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE))
          conflict();
      }
      throw ex;
    }
    if (java.util.Set.of("releases", "snapshots", "operations", "sql_versions")
        .contains(collection)) {
      String subject = node.path(collection.equals("sql_versions") ? "sqlId" : "jobId").asText();
      jdbc.update(
          "DELETE FROM platform_record_subjects WHERE collection=? AND record_id=?",
          collection,
          id);
      if (!subject.isBlank())
        jdbc.update(
            "INSERT INTO platform_record_subjects(collection,record_id,subject_id,created_at)"
                + " VALUES (?,?,?,?)",
            collection,
            id,
            subject,
            node.path("createdAt").asText(""));
      pruneHistory(collection, subject);
    }
    node.put("revision", expected + 1);
    return node;
  }

  /** SQL 保存版本和作业发布版本按作业各留最新五次，旧记录读取时也执行收敛。 */
  private void pruneHistory(String collection, String subject) {
    if (!java.util.Set.of("releases", "sql_versions").contains(collection) || subject.isBlank()) return;
    // Instant 的小数位可变，字符串比较会把同一秒内较早的短时间戳排到前面。
    var history = jdbc.query("SELECT record_id,created_at FROM platform_record_subjects WHERE collection=? AND subject_id=?", (rs, row) -> java.util.Map.entry(rs.getString(1), Instant.parse(rs.getString(2))), collection, subject);
    var ids = history.stream().sorted(java.util.Comparator.comparing((java.util.Map.Entry<String, Instant> entry) -> entry.getValue()).thenComparing(java.util.Map.Entry::getKey).reversed()).map(java.util.Map.Entry::getKey).toList();
    for (String id : ids.stream().skip(5).toList()) {
      jdbc.update("DELETE FROM platform_records WHERE collection=? AND id=?", collection, id);
      jdbc.update("DELETE FROM platform_record_subjects WHERE collection=? AND record_id=?", collection, id);
    }
  }

  /** 按集合、标识和修订号删除元数据，拒绝并发覆盖。 */
  @org.springframework.transaction.annotation.Transactional
  public void delete(String collection, String id, long revision) {
    if (jdbc.update(
            "DELETE FROM platform_records WHERE collection=? AND id=? AND revision=?",
            collection,
            id,
            revision)
        != 1) conflict();
    jdbc.update(
        "DELETE FROM platform_record_subjects WHERE collection=? AND record_id=?", collection, id);
  }

  /** 创建元数据修订冲突异常。 */
  private void conflict() {
    throw new BusinessException(HttpStatus.CONFLICT, "REVISION_CONFLICT", "记录已被修改，请刷新后重试");
  }
}
