package com.flinkweb.controller.job.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.job.runtime.DiagnosticService;
import com.flinkweb.service.job.runtime.SnapshotService;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 作业按需诊断、日志与快照台账接口。 */
@RestController
@RequestMapping("/api")
public class JobObservationController {
  private final JobService jobs;
  private final DiagnosticService diagnostics;
  private final SnapshotService snapshots;

  /** 注入本模块接口依赖，不在控制器内执行持久化逻辑。 */
  public JobObservationController(
      JobService jobs, DiagnosticService diagnostics, SnapshotService snapshots) {
    this.jobs = jobs;
    this.diagnostics = diagnostics;
    this.snapshots = snapshots;
  }

  /** 只有显式点击时才读取事件和 Pod 概览。 */
  @PostMapping("/jobs/{id}/diagnostics")
  public ApiResponse<ObjectNode> diagnostics(@PathVariable String id) {
    return ApiResponse.ok(diagnostics.inspect(id));
  }

  /** 按权限读取受限日志，默认不跟随，最多一万行。 */
  @PostMapping("/jobs/{id}/logs")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','OPERATOR')")
  public ApiResponse<ObjectNode> logs(@PathVariable String id, @RequestBody ObjectNode input) {
    return ApiResponse.ok(
        diagnostics.log(
            id,
            input.path("pod").asText(),
            input.path("container").asText(),
            input.path("lines").asInt(10000),
            input.path("previous").asBoolean()));
  }

  /** 返回本地快照台账，不读取 Kubernetes 或 S3。 */
  @GetMapping("/jobs/{id}/snapshots")
  public ApiResponse<List<ObjectNode>> snapshots(@PathVariable String id) {
    jobs.get(id);
    return ApiResponse.ok(snapshots.list(id));
  }

  /** 手动检查一条已登记快照的元数据对象。 */
  @PostMapping("/jobs/{id}/snapshots/{snapshot}/check")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','OPERATOR')")
  public ApiResponse<ObjectNode> checkSnapshot(
      @PathVariable String id, @PathVariable String snapshot) {
    jobs.get(id);
    return ApiResponse.ok(snapshots.check(id, snapshot));
  }
}
