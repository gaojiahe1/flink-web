package com.flinkweb.controller.sql;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.dto.sql.SqlCheckResult;
import com.flinkweb.dto.sql.SqlDraftRequest;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.service.sql.SqlJobService;
import com.flinkweb.service.sql.SqlValidationService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** SQL 开发与发布 HTTP 入口，角色权限与 JAR 作业一致。 */
@RestController
@RequestMapping("/api/sql")
public class SqlJobController {
  private final SqlJobService jobs;
  private final SqlValidationService validation;
  private final com.flinkweb.service.sql.SqlParserService parser;
  private final java.nio.file.Path runnerJar;

  /** 注入 SQL 草稿管理和基础校验服务。 */
  public SqlJobController(
      SqlJobService jobs,
      SqlValidationService validation,
      com.flinkweb.service.sql.SqlParserService parser,
      @org.springframework.beans.factory.annotation.Value(
              "${platform.sql-validation.runner-jar:./sql-runner/target/flink-web-sql-runner.jar}")
          String runnerJar) {
    this.jobs = jobs;
    this.validation = validation;
    this.parser = parser;
    this.runnerJar = java.nio.file.Path.of(runnerJar).toAbsolutePath().normalize();
  }

  /** 下载平台随版本发布的 Runner；用户仍需上传并显式选择文件，既有作业不自动替换。 */
  @GetMapping("/runner/download")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public org.springframework.http.ResponseEntity<org.springframework.core.io.Resource> runner() {
    if (!java.nio.file.Files.isRegularFile(runnerJar))
      throw new com.flinkweb.exception.common.BusinessException(
          org.springframework.http.HttpStatus.NOT_FOUND,
          "SQL_RUNNER_NOT_INSTALLED",
          "平台未安装 SQL Runner，请按部署指南构建并上传");
    return org.springframework.http.ResponseEntity.ok()
        .header("Content-Disposition", "attachment; filename=flink-web-sql-runner.jar")
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
        .body(new org.springframework.core.io.FileSystemResource(runnerJar));
  }

  /** 返回 SQL 草稿列表。 */
  @GetMapping("/jobs")
  public ApiResponse<List<ObjectNode>> list() {
    return ApiResponse.ok(jobs.list());
  }

  /** 返回单个 SQL 草稿。 */
  @GetMapping("/jobs/{id}")
  public ApiResponse<ObjectNode> get(@PathVariable String id) {
    return ApiResponse.ok(jobs.get(id));
  }

  /** 创建或以修订号更新 SQL 草稿。 */
  @PutMapping("/jobs/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<ObjectNode> save(
      @PathVariable String id, @Valid @RequestBody SqlDraftRequest input) {
    return ApiResponse.ok(jobs.save(id, input));
  }

  /** 检查脚本的基础结构，明确返回 BASIC 校验级别。 */
  @PostMapping("/validate")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<SqlCheckResult> validate(@RequestBody ObjectNode input) {
    ObjectNode runtime =
        input.path("runtime").isObject()
            ? (ObjectNode) input.path("runtime")
            : input.objectNode().put("imageId", input.path("imageId").asText());
    return ApiResponse.ok(jobs.check(input.path("sql").asText(), runtime));
  }

  /** 生成 SQL Application 的 YAML 预览。 */
  @PostMapping("/preview")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<ObjectNode> preview(@Valid @RequestBody SqlDraftRequest input) {
    return ApiResponse.ok(jobs.preview(input));
  }

  /** 将指定版本的 SQL 保存为部署草稿，返回普通作业管理标识。 */
  @PostMapping("/jobs/{id}/publish")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<JobDraft> publish(@PathVariable String id, @RequestBody ObjectNode input) {
    return ApiResponse.ok(jobs.publish(id, input.path("revision").asLong(-1)));
  }

  /** 删除没有关联部署登记的 SQL 草稿。 */
  @DeleteMapping("/jobs/{id}")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<Void> delete(@PathVariable String id, @RequestParam long revision) {
    jobs.delete(id, revision);
    return ApiResponse.ok(null);
  }
}
