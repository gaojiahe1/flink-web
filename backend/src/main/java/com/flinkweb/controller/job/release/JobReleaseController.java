package com.flinkweb.controller.job.release;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.job.release.ReleaseService;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 作业不可变发布记录与草稿恢复接口。 */
@RestController
@RequestMapping("/api")
public class JobReleaseController {
  private final JobService jobs;
  private final ReleaseService releases;

  /** 注入本模块接口依赖，不在控制器内执行持久化逻辑。 */
  public JobReleaseController(JobService jobs, ReleaseService releases) {
    this.jobs = jobs;
    this.releases = releases;
  }

  /** 读取指定作业的发布历史。 */
  @GetMapping("/jobs/{id}/releases")
  public ApiResponse<List<ObjectNode>> releases(@PathVariable String id) {
    jobs.get(id);
    return ApiResponse.ok(releases.list(id));
  }

  /** 从历史版本恢复声明式草稿，不自动部署或恢复运行状态。 */
  @PostMapping("/jobs/{id}/releases/{release}/restore")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<JobDraft> restore(
      @PathVariable String id, @PathVariable String release, @RequestBody ObjectNode input) {
    return ApiResponse.ok(releases.restore(id, release, input.path("revision").asLong(-1), jobs));
  }
}
