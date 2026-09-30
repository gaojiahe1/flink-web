package com.flinkweb.service.job.management.impl;

import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.job.JobBindingRepository;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.job.management.JobManagementService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 编排删除草稿前的集群存在性检查，防止遗失仍在运行的作业登记。 */
@Service
public class JobManagementServiceImpl implements JobManagementService {
  private final JobService jobs;
  private final KubernetesClient client;
  private final RecordRepository records;
  private final JobBindingRepository bindings;

  /** 注入草稿、资源读取及登记仓库，用于删除前检查。 */
  public JobManagementServiceImpl(
      JobService jobs,
      KubernetesClient client,
      RecordRepository records,
      JobBindingRepository bindings) {
    this.jobs = jobs;
    this.client = client;
    this.records = records;
    this.bindings = bindings;
  }

  /** 确认已部署或纳管资源不再存在，之后以乐观锁删除本地草稿。 */
  public void deleteDraft(String id, long revision) {
    var job = jobs.get(id);
    boolean deployed =
        bindings.findByJobId(id).isPresent()
            || records.list("operations").stream()
                .anyMatch(
                    op ->
                        id.equals(op.path("jobId").asText())
                            && !"REJECTED".equals(op.path("state").asText()));
    if (deployed && !client.call("GET", client.base() + "/" + job.deploymentName(), null).isMissingNode())
      throw new BusinessException(
          HttpStatus.CONFLICT, "DEPLOYMENT_EXISTS", "请先删除集群资源并确认完成，再删除平台记录");
    jobs.delete(id, revision);
  }
}
