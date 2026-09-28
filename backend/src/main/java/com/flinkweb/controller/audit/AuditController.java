package com.flinkweb.controller.audit;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.service.audit.AuditService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 提供管理员审计记录查询接口。 */
@RestController
@RequestMapping("/api/audit")
@PreAuthorize("hasRole('ADMIN')")
public class AuditController {
  private final AuditService service;

  /** 注入审计查询服务。 */
  public AuditController(AuditService service) {
    this.service = service;
  }

  /** 返回管理员可见的审计记录。 */
  @GetMapping
  public ApiResponse<List<ObjectNode>> list() {
    return ApiResponse.ok(service.list());
  }
}
