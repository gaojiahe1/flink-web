package com.flinkweb.service.job.management;

/** 服务契约：编排删除草稿前的集群存在性检查，防止遗失仍在运行的作业登记。 */
public interface JobManagementService {
  /** 确认已部署或纳管资源不再存在，之后以乐观锁删除本地草稿。 */
  void deleteDraft(String id, long revision);
}
