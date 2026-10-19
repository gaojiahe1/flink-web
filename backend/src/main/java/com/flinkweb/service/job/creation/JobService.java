package com.flinkweb.service.job.creation;

import com.flinkweb.dto.job.creation.DraftRequest;
import com.flinkweb.entity.job.JobDraft;
import java.util.List;

/** 服务契约：维护作业草稿，处理 YAML 语法、名称不可变约束、乐观锁及事务审计。 */
public interface JobService {
  /** 查询本地已登记草稿，不访问集群状态。 */
  List<JobDraft> list();

  /** 仅返回名称是否可用，不泄露其他作业内容。 */
  boolean nameAvailable(String name, String excludeId);

  /** 读取指定草稿，不存在时返回明确业务错误。 */
  JobDraft get(String id);

  /** 校验 YAML 语法，在同一事务内新增草稿和审计记录。 */
  JobDraft create(DraftRequest request);

  /** SQL 发布复用已经由该草稿占用的名称，不允许普通新建接口抢占。 */
  JobDraft createSql(DraftRequest request, String sqlId);

  /** 在同一事务中占用全局名称，数据库唯一键保护 SQL/JAR 并发创建。 */
  void reserveName(String name, String owner);

  /** 删除资源时只释放匹配归属的名称。 */
  void releaseName(String name, String owner);

  /** 保持作业名称不变，校验 YAML 后以修订号更新草稿及审计。 */
  JobDraft update(String id, DraftRequest request);

  /** 将配置和专属依赖复制为新编号草稿，不复制运行状态与权限。 */
  JobDraft copy(String id, com.flinkweb.dto.job.creation.CopyJobRequest request);

  /** 以修订号删除草稿及资源绑定，并在同一事务内记录审计。 */
  void delete(String id, long revision);
}
