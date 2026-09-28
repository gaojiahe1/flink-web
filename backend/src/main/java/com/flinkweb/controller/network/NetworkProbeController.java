package com.flinkweb.controller.network;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.dto.network.NetworkProbeRequest;
import com.flinkweb.service.network.NetworkProbeService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 管理员手动诊断后端到目标主机的端口可达性。 */
@RestController
@RequestMapping("/api/platform/network")
public class NetworkProbeController {
  private final NetworkProbeService service;

  /** 注入单目标诊断服务。 */
  public NetworkProbeController(NetworkProbeService service) {
    this.service = service;
  }

  /** 仅检查用户指定的单个端口，不支持范围扫描。 */
  @PostMapping("/test")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<ObjectNode> test(@Valid @RequestBody NetworkProbeRequest input) {
    return ApiResponse.ok(service.test(input.host(), input.port().intValueExact()));
  }
}
