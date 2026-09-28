package com.flinkweb.controller.job.management;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.dto.job.management.AdoptJobRequest;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.job.management.JobAdoptionService;
import com.flinkweb.service.job.management.JobManagementService;
import com.flinkweb.service.job.management.JobQueryService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 作业列表、按需刷新、纳管和草稿移除接口。 */
@RestController
@RequestMapping("/api/jobs")
public class JobManagementController {
  private final JobService jobs;
  private final JobQueryService queries;
  private final JobAdoptionService adoption;
  private final JobManagementService management;

  /** 注入本模块接口依赖，不在控制器内执行持久化逻辑。 */
  public JobManagementController(
      JobService jobs,
      JobQueryService queries,
      JobAdoptionService adoption,
      JobManagementService management) {
    this.jobs = jobs;
    this.queries = queries;
    this.adoption = adoption;
    this.management = management;
  }

  /** 查询平台已登记草稿，不访问 Kubernetes。 */
  @GetMapping
  public ApiResponse<List<JobDraft>> list() {
    return ApiResponse.ok(jobs.list());
  }

  /** 查询指定作业草稿。 */
  @GetMapping("/{id}")
  public ApiResponse<JobDraft> get(@PathVariable String id) {
    return ApiResponse.ok(jobs.get(id));
  }

  /** 按本次页面请求采集集群状态及既有作业，不建立后台定时任务。 */
  @PostMapping("/refresh")
  public ApiResponse<ObjectNode> refresh() {
    return ApiResponse.ok(queries.refresh());
  }

  /** 接收管理员确认的资源身份，将现有作业纳入平台登记。 */
  @PostMapping("/adopt")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<JobDraft> adopt(@Valid @RequestBody AdoptJobRequest request) {
    return ApiResponse.ok(adoption.adopt(request));
  }

  /** 检查集群资源已删除后移除本地草稿。 */
  @DeleteMapping("/{id}/draft")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<Void> delete(@PathVariable String id, @RequestParam long revision) {
    management.deleteDraft(id, revision);
    return ApiResponse.ok(null);
  }
}
