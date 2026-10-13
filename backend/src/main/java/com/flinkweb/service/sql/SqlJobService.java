package com.flinkweb.service.sql;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.sql.SqlDraftRequest;
import com.flinkweb.entity.job.JobDraft;
import java.util.*;

/** 服务契约：管理 SQL 开发草稿并生成普通 Application 部署草稿，运行操作复用已有作业管理接口。 */
public interface SqlJobService {
  /** 列出 SQL 开发草稿，不访问 Kubernetes 或外部数据源。 */
  List<ObjectNode> list();

  /** 读取指定 SQL 草稿及其已关联的部署信息。 */
  ObjectNode get(String id);

  /** 保存尚在编写的 SQL，使用修订号保护并发修改；不要求未完成脚本通过执行检查。 */
  ObjectNode save(String id, SqlDraftRequest input);

  /** 读取本作业不可变的 SQL 保存版本，仅查询元数据。 */
  List<ObjectNode> versions(String id);

  /** 将历史 SQL 和运行配置恢复为新草稿修订，不触发集群变更。 */
  ObjectNode restoreVersion(String id, String version, long revision);

  /** 检查用户 SQL 及选中 Catalog 的初始化脚本。 */
  com.flinkweb.dto.sql.SqlCheckResult check(String script, ObjectNode runtime);

  /** 校验 SQL 并生成 YAML 预览；不保存部署草稿，也不访问 Kubernetes。 */
  ObjectNode preview(SqlDraftRequest input);

  /** 渲染和回填部署配置，不要求未完成的 SQL 脚本通过检查。 */
  ObjectNode configuration(SqlDraftRequest input);

  /** 在同一数据库事务中发布为部署草稿；仅显式运行操作才会提交 Kubernetes。 */
  JobDraft publish(String id, long revision);

  /** 已发布草稿需先从作业管理移除对应部署登记，防止孤立运行资源。 */
  void delete(String id, long revision);
}
