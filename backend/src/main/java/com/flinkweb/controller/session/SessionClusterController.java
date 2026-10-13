package com.flinkweb.controller.session;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.dto.session.SessionRequest;
import com.flinkweb.dto.session.SessionStartRequest;
import com.flinkweb.service.session.SessionClusterService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 共享 Session 基础设施接口；配置由管理员维护，运行操作允许运维角色。 */
@RestController
@RequestMapping("/api/sessions")
public class SessionClusterController {
  private final SessionClusterService sessions;
  private final com.flinkweb.service.job.runtime.DiagnosticService diagnostics;

  /** 注入 Session 集群服务。 */
  public SessionClusterController(
      SessionClusterService sessions,
      com.flinkweb.service.job.runtime.DiagnosticService diagnostics) {
    this.sessions = sessions;
    this.diagnostics = diagnostics;
  }

  /** 按需读取 Session 的 Pod 与事件，沿用作业诊断响应。 */
  @PostMapping("/{id}/diagnostics")
  public ApiResponse<ObjectNode> diagnostics(@PathVariable String id) {
    return ApiResponse.ok(diagnostics.inspectSession(id));
  }

  /** 管理员和运维可读取归属校验、脱敏及受限的容器日志。 */
  @PostMapping("/{id}/logs")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
  public ApiResponse<ObjectNode> logs(@PathVariable String id, @RequestBody ObjectNode input) {
    return ApiResponse.ok(
        diagnostics.sessionLog(
            id,
            input.path("pod").asText(),
            input.path("container").asText(),
            input.path("lines").asInt(10000),
            input.path("previous").asBoolean()));
  }

  /** 查询平台配置列表。 */
  @GetMapping
  public ApiResponse<List<ObjectNode>> list() {
    return ApiResponse.ok(sessions.list());
  }

  /** 表单/YAML 转换与本地配置校验，不保存或启动集群。 */
  @PostMapping("/configuration")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<ObjectNode> configuration(@Valid @RequestBody SessionRequest input) {
    return ApiResponse.ok(sessions.configuration(input));
  }

  /** 创建或更新配置。 */
  @PutMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<ObjectNode> save(
      @PathVariable String id, @Valid @RequestBody SessionRequest input) {
    return ApiResponse.ok(sessions.save(id, input));
  }

  /** 查询单个集群真实状态。 */
  @GetMapping("/{id}/status")
  public ApiResponse<ObjectNode> status(@PathVariable String id) {
    return ApiResponse.ok(sessions.status(id));
  }

  /** 显式启动集群。 */
  @PostMapping("/{id}/start")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
  public ApiResponse<ObjectNode> start(
      @PathVariable String id, @Valid @RequestBody SessionStartRequest input) {
    return ApiResponse.ok(sessions.start(id, input.revision()));
  }

  /** 显式停止集群，集群内作业受 Operator 删除策略约束。 */
  @PostMapping("/{id}/stop")
  @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
  public ApiResponse<ObjectNode> stop(@PathVariable String id) {
    return ApiResponse.ok(sessions.stop(id));
  }

  /** 删除已停止集群的配置。 */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<Void> delete(@PathVariable String id, @RequestParam long revision) {
    sessions.delete(id, revision);
    return ApiResponse.ok(null);
  }
}
