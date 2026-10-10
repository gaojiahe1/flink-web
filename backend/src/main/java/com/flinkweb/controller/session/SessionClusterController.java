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

  /** 注入 Session 集群服务。 */
  public SessionClusterController(SessionClusterService sessions) {
    this.sessions = sessions;
  }

  /** 查询平台配置列表。 */
  @GetMapping
  public ApiResponse<List<ObjectNode>> list() {
    return ApiResponse.ok(sessions.list());
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
