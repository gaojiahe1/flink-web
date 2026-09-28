package com.flinkweb.controller.job.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.service.job.runtime.OperationService;
import java.security.Principal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 作业运行操作和幂等历史接口。 */
@RestController
@RequestMapping("/api/jobs")
public class JobOperationController {
  private final OperationService operations;

  /** 注入本模块接口依赖，不在控制器内执行持久化逻辑。 */
  public JobOperationController(OperationService operations) {
    this.operations = operations;
  }

  /** 将已授权的部署、启停或快照指令交给操作服务执行。 */
  @PostMapping("/{id}/actions/{action}")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','OPERATOR')")
  public ApiResponse<ObjectNode> action(
      @PathVariable String id,
      @PathVariable String action,
      @RequestBody ObjectNode input,
      Principal user) {
    return ApiResponse.ok(operations.execute(id, action, input, user));
  }

  /** 查询指定作业的操作记录。 */
  @GetMapping("/{id}/operations")
  public ApiResponse<List<ObjectNode>> history(@PathVariable String id) {
    return ApiResponse.ok(operations.history(id));
  }
}
