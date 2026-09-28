package com.flinkweb.controller.job.creation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.dto.job.creation.DraftRequest;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.service.job.creation.FormService;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.job.runtime.OperationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 作业创建、表单生成、YAML 校验和草稿修改接口。 */
@RestController
@RequestMapping("/api/jobs")
public class JobCreationController {
  private final JobService jobs;
  private final OperationService operations;
  private final FormService forms;

  /** 注入本模块接口依赖，不在控制器内执行持久化逻辑。 */
  public JobCreationController(JobService jobs, OperationService operations, FormService forms) {
    this.jobs = jobs;
    this.operations = operations;
    this.forms = forms;
  }

  /** 执行本地 YAML 校验，并按请求选择 Kubernetes dry-run。 */
  @PostMapping("/validate")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<ObjectNode> validate(@RequestBody ObjectNode input) {
    return ApiResponse.ok(
        operations.validate(
            input.path("yaml").asText(),
            input.path("name").asText(),
            input.path("dryRun").asBoolean()));
  }

  /** 将表单输入渲染为可供预览和编辑的 YAML。 */
  @PostMapping("/render-form")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<ObjectNode> render(@RequestBody ObjectNode input) {
    return ApiResponse.ok(forms.render(input));
  }

  /** 校验输入并创建作业草稿，返回创建结果。 */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<JobDraft> create(@Valid @RequestBody DraftRequest input) {
    return ApiResponse.ok(jobs.create(input));
  }

  /** 按修订号更新草稿，避免覆盖其他人的修改。 */
  @PutMapping("/{id}/draft")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<JobDraft> update(
      @PathVariable String id, @Valid @RequestBody DraftRequest input) {
    return ApiResponse.ok(jobs.update(id, input));
  }
}
