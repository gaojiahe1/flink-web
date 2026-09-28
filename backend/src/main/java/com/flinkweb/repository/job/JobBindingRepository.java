package com.flinkweb.repository.job;

import com.flinkweb.datasource.metadata.SchemaInitializer;
import com.flinkweb.entity.job.JobBinding;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 使用 JDBC 维护作业与 Kubernetes 资源身份的持久化绑定。 */
@Repository
public class JobBindingRepository {
  private final JdbcTemplate jdbc;
  private static final RowMapper<JobBinding> MAPPER =
      (rs, index) ->
          new JobBinding(
              rs.getString("job_id"),
              rs.getString("namespace"),
              rs.getString("resource_name"),
              rs.getString("resource_uid"),
              rs.getString("resource_version"),
              Instant.parse(rs.getString("imported_at")));

  /** 注入 JDBC 模板，供资源身份绑定读写使用。 */
  public JobBindingRepository(JdbcTemplate jdbc, SchemaInitializer ignored) {
    this.jdbc = jdbc;
  }

  /** 读取指定作业的 Namespace 与 UID 绑定。 */
  public Optional<JobBinding> findByJobId(String jobId) {
    return jdbc.query("SELECT * FROM job_bindings WHERE job_id=?", MAPPER, jobId).stream()
        .findFirst();
  }

  /** 读取全部绑定，用于将实时快照与本地登记匹配。 */
  public List<JobBinding> findAll() {
    return jdbc.query("SELECT * FROM job_bindings", MAPPER);
  }

  /** 插入资源身份绑定，以唯一约束防止重复纳管。 */
  public void insert(JobBinding binding) {
    jdbc.update(
        "INSERT INTO"
            + " job_bindings(job_id,namespace,resource_name,resource_uid,resource_version,imported_at)"
            + " VALUES (?,?,?,?,?,?)",
        binding.jobId(),
        binding.namespace(),
        binding.name(),
        binding.resourceUid(),
        binding.resourceVersion(),
        binding.importedAt().toString());
  }

  /** 删除指定作业的身份绑定。 */
  public void delete(String jobId) {
    jdbc.update("DELETE FROM job_bindings WHERE job_id=?", jobId);
  }
}
