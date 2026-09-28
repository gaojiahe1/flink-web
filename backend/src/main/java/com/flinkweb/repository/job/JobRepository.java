package com.flinkweb.repository.job;

import com.flinkweb.entity.job.JobDraft;
import java.util.List;
import java.util.Optional;

/** 定义作业草稿持久化接口，使业务层无需关心数据库类型和 SQL。 */
public interface JobRepository {
  /** 返回已登记的全部作业草稿。 */
  List<JobDraft> findAll();

  /** 按平台标识查找草稿。 */
  Optional<JobDraft> findById(String id);

  /** 按 Kubernetes 作业名称查找草稿。 */
  Optional<JobDraft> findByName(String name);

  /** 保存新的作业草稿。 */
  void insert(JobDraft draft);

  /** 使用期望修订号更新草稿，并报告是否发生并发冲突。 */
  boolean update(JobDraft draft, long expectedRevision);

  /** 使用期望修订号删除草稿，并报告是否删除成功。 */
  boolean delete(String id, long expectedRevision);
}
