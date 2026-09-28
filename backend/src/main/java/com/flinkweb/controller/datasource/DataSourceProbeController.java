package com.flinkweb.controller.datasource;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.service.audit.AuditService;
import com.flinkweb.service.datasource.DataSourceProbeService;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 用户发起的数据源连接与字段探查接口。 */
@RestController
@RequestMapping("/api")
public class DataSourceProbeController {
  private final DataSourceProbeService probes;
  private final AuditService audit;

  /** 注入本模块接口依赖，不在控制器内执行持久化逻辑。 */
  public DataSourceProbeController(DataSourceProbeService probes, AuditService audit) {
    this.probes = probes;
    this.audit = audit;
  }

  /** 测试已登记数据源或读取其指定表的字段。 */
  @PostMapping("/datasources/{id}/probe")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<ObjectNode> probe(@PathVariable String id, @RequestBody ObjectNode input) {
    var result = probes.probe(id, input.path("schema").asBoolean());
    audit.record("DATASOURCE_PROBE", id, "SUCCESS");
    return ApiResponse.ok(result);
  }
}
