package com.flinkweb.controller.sql;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.dto.sql.SqlVerificationRequest;
import com.flinkweb.service.sql.SqlExecutionService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** SQL 逻辑验证 HTTP 入口，运行和结果读取均要求开发角色。 */
@RestController
@RequestMapping("/api/sql")
@PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
public class SqlExecutionController {
  private final SqlExecutionService executions;

  /** 注入逻辑验证服务。 */
  public SqlExecutionController(SqlExecutionService executions) {
    this.executions = executions;
  }

  /** 提交保存修订的短时验证。 */
  @PostMapping("/jobs/{id}/verifications")
  public ApiResponse<ObjectNode> submit(
      @PathVariable String id, @Valid @RequestBody SqlVerificationRequest input) {
    return ApiResponse.ok(executions.submit(id, input.revision(), input.requestId()));
  }

  /** 读取当前验证状态及固定上限的数据。 */
  @GetMapping("/verifications/{id}")
  public ApiResponse<ObjectNode> status(@PathVariable String id) {
    return ApiResponse.ok(executions.status(id));
  }

  /** 显式取消验证。 */
  @PostMapping("/verifications/{id}/cancel")
  public ApiResponse<ObjectNode> cancel(@PathVariable String id) {
    return ApiResponse.ok(executions.cancel(id));
  }
}
