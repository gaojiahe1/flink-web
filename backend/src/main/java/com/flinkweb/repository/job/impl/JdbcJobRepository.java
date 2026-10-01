package com.flinkweb.repository.job.impl;

import com.flinkweb.datasource.metadata.SchemaInitializer;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.repository.job.JobRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 使用参数化 JDBC SQL 读写作业草稿，并以修订号实现乐观锁。 */
@Repository
public class JdbcJobRepository implements JobRepository {
  private final JdbcTemplate jdbc;
  private static final RowMapper<JobDraft> MAPPER =
      (rs, row) ->
          new JobDraft(
              rs.getString("id"),
              rs.getString("name"),
              rs.getString("description"),
              rs.getString("flink_version"),
              rs.getString("yaml_text"),
              rs.getLong("revision"),
              Instant.parse(rs.getString("created_at")),
              Instant.parse(rs.getString("updated_at")),
              rs.getLong("job_number"),
              rs.getString("deployment_name"));

  private static final String SELECT =
      "SELECT d.*,n.job_number,n.deployment_name FROM job_drafts d JOIN job_numbers n ON"
          + " d.id=n.job_id";

  /** 注入 JDBC 模板，供草稿持久化操作使用。 */
  public JdbcJobRepository(JdbcTemplate jdbc, SchemaInitializer ignored) {
    this.jdbc = jdbc;
  }

  /** 按更新时间查询全部作业草稿。 */
  public List<JobDraft> findAll() {
    return jdbc.query(SELECT + " ORDER BY updated_at DESC, id", MAPPER);
  }

  /** 根据平台作业标识查询草稿。 */
  public Optional<JobDraft> findById(String id) {
    return jdbc.query(SELECT + " WHERE d.id = ?", MAPPER, id).stream().findFirst();
  }

  /** 按作业名称查询草稿，用于纳管同名冲突检测。 */
  public Optional<JobDraft> findByName(String name) {
    return jdbc.query(SELECT + " WHERE d.name=?", MAPPER, name).stream().findFirst();
  }

  /** 插入新的作业草稿；名称重复由数据库唯一约束拒绝。 */
  public void insert(JobDraft draft) {
    jdbc.update(
        "INSERT INTO job_numbers(job_id,deployment_name) VALUES (?,?)",
        draft.id(),
        draft.deploymentName());
    if (draft.deploymentName() == null) {
      long number =
          jdbc.queryForObject(
              "SELECT job_number FROM job_numbers WHERE job_id=?", Long.class, draft.id());
      String deploymentName = JobDraft.numberedName(number);
      // 跳过老环境已经占用的资源名，保留历史身份。
      while (jdbc.queryForObject(
              "SELECT COUNT(*) FROM job_numbers WHERE deployment_name=?",
              Long.class,
              deploymentName)
          > 0) {
        jdbc.update(
            "UPDATE job_numbers SET job_id=? WHERE job_id=?", "reserved-" + number, draft.id());
        jdbc.update("INSERT INTO job_numbers(job_id) VALUES (?)", draft.id());
        number =
            jdbc.queryForObject(
                "SELECT job_number FROM job_numbers WHERE job_id=?", Long.class, draft.id());
        deploymentName = JobDraft.numberedName(number);
      }
      jdbc.update(
          "UPDATE job_numbers SET deployment_name=? WHERE job_id=?", deploymentName, draft.id());
    }
    jdbc.update(
        """
INSERT INTO job_drafts(id,name,description,flink_version,yaml_text,revision,created_at,updated_at)
VALUES (?,?,?,?,?,?,?,?)
""",
        draft.id(),
        draft.name(),
        draft.description(),
        draft.flinkVersion(),
        draft.yaml(),
        draft.revision(),
        draft.createdAt().toString(),
        draft.updatedAt().toString());
  }

  /** 通过旧修订号进行条件更新，返回是否成功。 */
  public boolean update(JobDraft draft, long expectedRevision) {
    return jdbc.update(
            """
UPDATE job_drafts SET name=?,description=?,flink_version=?,yaml_text=?,revision=?,updated_at=?
WHERE id=? AND revision=?
""",
            draft.name(),
            draft.description(),
            draft.flinkVersion(),
            draft.yaml(),
            draft.revision(),
            draft.updatedAt().toString(),
            draft.id(),
            expectedRevision)
        == 1;
  }

  /** 在旧资源确认不存在后持久化稳定的编号资源名。 */
  public void useNumberedName(String id) {
    var job = findById(id).orElseThrow();
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM job_numbers WHERE deployment_name=? AND job_id<>?",
            Long.class,
            JobDraft.numberedName(job.jobNumber()),
            id)
        > 0)
      throw new com.flinkweb.exception.common.BusinessException(
          org.springframework.http.HttpStatus.CONFLICT,
          "DEPLOYMENT_NAME_CONFLICT",
          "编号资源名被历史资源占用，请先停止并迁移占用该名称的作业");
    jdbc.update(
        "UPDATE job_numbers SET deployment_name=? WHERE job_id=?",
        JobDraft.numberedName(job.jobNumber()),
        id);
  }

  /** 仅在修订号匹配时删除草稿，返回是否成功。 */
  public boolean delete(String id, long expectedRevision) {
    return jdbc.update("DELETE FROM job_drafts WHERE id=? AND revision=?", id, expectedRevision)
        == 1;
  }
}
