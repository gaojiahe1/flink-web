package com.flinkweb.controller.ai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.service.ai.AiTaskService;
import java.util.List;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/** 显式 AI 任务入口；模型输出只能作为建议，不能进入作业控制操作。 */
@RestController
@RequestMapping("/api/ai")
public class AiTaskController {
  private final AiTaskService tasks;
  /** 注入异步任务及确定性编译服务。 */
  public AiTaskController(AiTaskService tasks) {this.tasks=tasks;}
  /** 生成 SQL 需要开发角色，诊断允许运维且仍由服务核验作业范围。 */
  @PostMapping("/tasks") @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','OPERATOR')")
  public ResponseEntity<ApiResponse<ObjectNode>> create(@RequestBody ObjectNode input) {
    boolean developer=SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
        .anyMatch(a->a.getAuthority().equals("ROLE_ADMIN")||a.getAuthority().equals("ROLE_DEVELOPER"));
    if(!developer&&!input.path("kind").asText().equals("diagnosis")) throw new BusinessException(HttpStatus.FORBIDDEN,"FORBIDDEN","SQL 助手需要开发权限");
    return ResponseEntity.accepted().body(ApiResponse.ok(tasks.create(input)));
  }
  /** 主动请求期间查询任务状态，不触发 Kubernetes 查询。 */
  @GetMapping("/tasks/{id}")
  public ApiResponse<ObjectNode> get(@PathVariable String id) {return ApiResponse.ok(tasks.get(id));}
  /** 只取消当前用户的任务。 */
  @PostMapping("/tasks/{id}/cancel")
  public ApiResponse<ObjectNode> cancel(@PathVariable String id) {return ApiResponse.ok(tasks.cancel(id));}
  /** 当前用户的历史任务摘要和产物。 */
  @GetMapping("/tasks")
  public ApiResponse<List<ObjectNode>> history(@RequestParam(defaultValue="") String sqlId) {return ApiResponse.ok(tasks.history(sqlId));}
  /** 手动或 AI 起草的数据定义均通过相同确定性编译规则。 */
  @PostMapping("/definitions/compile") @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<ObjectNode> compile(@RequestBody ObjectNode input) {return ApiResponse.ok(tasks.compile(input));}
}
