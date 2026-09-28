package com.flinkweb.controller.sql;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.sql.SqlJobService;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** SQL 保存历史与草稿恢复接口。 */
@RestController
@RequestMapping("/api")
public class SqlHistoryController {
  private final JobService jobs;
  private final SqlJobService sql;

  /** 注入本模块接口依赖，不在控制器内执行持久化逻辑。 */
  public SqlHistoryController(JobService jobs, SqlJobService sql) {
    this.jobs = jobs;
    this.sql = sql;
  }

  /** 返回 SQL 保存历史，供编辑器比较和恢复。 */
  @GetMapping("/sql/jobs/{id}/versions")
  public ApiResponse<List<ObjectNode>> sqlVersions(@PathVariable String id) {
    return ApiResponse.ok(sql.versions(id));
  }

  /** 恢复历史 SQL 至当前草稿并生成新修订；不自动重新发布。 */
  @PostMapping("/sql/jobs/{id}/versions/{version}/restore")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<ObjectNode> restoreSql(
      @PathVariable String id, @PathVariable String version, @RequestBody ObjectNode input) {
    return ApiResponse.ok(sql.restoreVersion(id, version, input.path("revision").asLong(-1)));
  }
}
