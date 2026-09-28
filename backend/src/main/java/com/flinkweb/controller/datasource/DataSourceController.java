package com.flinkweb.controller.datasource;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.service.datasource.DataSourceService;
import org.springframework.web.bind.annotation.*;

/** 提供数据源建表片段接口，登记维护沿用目录接口及管理员权限。 */
@RestController
@RequestMapping("/api/sql/datasources")
public class DataSourceController {
  private final DataSourceService service;
  private final com.flinkweb.service.datasource.DataSourceProbeService probes;

  /** 注入数据源片段生成服务。 */
  public DataSourceController(
      DataSourceService service, com.flinkweb.service.datasource.DataSourceProbeService probes) {
    this.service = service;
    this.probes = probes;
  }

  /** 开发人员按需浏览 MySQL 库表信息，不读取业务行。 */
  @PostMapping("/{id}/metadata")
  @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<ObjectNode> metadata(@PathVariable String id, @RequestBody ObjectNode input) {
    return ApiResponse.ok(
        probes.metadata(id, input.path("database").asText(), input.path("table").asText()));
  }

  /** 按当前登记生成 SQL 和密钥环境变量引用，不连接外部数据库。 */
  @GetMapping("/{id}/snippet")
  public ApiResponse<ObjectNode> snippet(@PathVariable String id) {
    return ApiResponse.ok(service.snippet(id));
  }
}
