package com.flinkweb.controller.system;

import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.dto.system.SystemInfo;
import com.flinkweb.service.system.SystemService;
import org.springframework.web.bind.annotation.*;

/** 提供平台版本、Namespace 和功能能力查询接口。 */
@RestController
@RequestMapping("/api/system")
public class SystemController {
  private final SystemService service;
  private final com.flinkweb.service.system.OperatorService operator;

  /** 注入系统信息查询服务。 */
  public SystemController(
      SystemService service, com.flinkweb.service.system.OperatorService operator) {
    this.service = service;
    this.operator = operator;
  }

  /** 显式获取 Operator 状态，供系统设置及创建作业的版本筛选使用。 */
  @GetMapping("/operator")
  public ApiResponse<com.fasterxml.jackson.databind.node.ObjectNode> operator() {
    return ApiResponse.ok(operator.inspect());
  }

  /** 返回平台版本、当前 Namespace、存储类型及功能说明。 */
  @GetMapping
  public ApiResponse<SystemInfo> info() {
    return ApiResponse.ok(service.info());
  }
}
