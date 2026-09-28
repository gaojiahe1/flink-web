package com.flinkweb.controller.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.permission.AccessService;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 作业归属和范围授权接口。 */
@RestController
@RequestMapping("/api")
public class JobAccessController {
  private final JobService jobs;
  private final AccessService access;

  /** 注入本模块接口依赖，不在控制器内执行持久化逻辑。 */
  public JobAccessController(JobService jobs, AccessService access) {
    this.jobs = jobs;
    this.access = access;
  }

  /** 返回当前用户各可见作业的操作权限，供页面禁用入口；不访问集群。 */
  @GetMapping("/platform/job-access")
  public ApiResponse<Map<String, Map<String, Boolean>>> jobAccess() {
    var permitted = access.evaluator();
    Map<String, Map<String, Boolean>> result = new LinkedHashMap<>();
    for (var job : jobs.list())
      result.put(
          job.id(),
          Map.of(
              "canEdit",
              permitted.test(job.id(), "edit"),
              "canOperate",
              permitted.test(job.id(), "operate")));
    return ApiResponse.ok(result);
  }

  /** 获取当前作业归属与页面权限。 */
  @GetMapping("/jobs/{id}/access")
  public ApiResponse<ObjectNode> access(@PathVariable String id) {
    jobs.get(id);
    return ApiResponse.ok(access.get(id));
  }

  /** 修改归属和监控链接，实际所有者校验在服务层。 */
  @PutMapping("/jobs/{id}/access")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<ObjectNode> saveAccess(
      @PathVariable String id, @RequestBody ObjectNode input) {
    jobs.get(id);
    return ApiResponse.ok(access.save(id, input));
  }
}
