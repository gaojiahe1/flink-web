package com.flinkweb.controller.ai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.service.ai.AiJobService;
import java.security.Principal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 作业助手的私有记忆与受控修复入口，读取不自动启动诊断。 */
@RestController
@RequestMapping("/api/ai/jobs/{jobId}")
@PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','OPERATOR')")
public class AiJobController {
  private final AiJobService jobs;
  /** 注入业务契约，控制器不访问模型、数据库或集群客户端。 */
  public AiJobController(AiJobService jobs) { this.jobs=jobs; }
  /** 恢复本用户在这个作业生命周期内的总结、消息、证据与建议。 */
  @GetMapping("/conversation")
  public ApiResponse<ObjectNode> get(@PathVariable String jobId) {return ApiResponse.ok(jobs.get(jobId));}
  /** 按类别取证，目标始终由作业归属决定，不能传入URL或Pod目标。 */
  @PostMapping("/evidence")
  public ApiResponse<ObjectNode> evidence(@PathVariable String jobId,@RequestBody ObjectNode input) {return ApiResponse.ok(jobs.evidence(jobId,input.path("category").asText("auto")));}
  /** 幂等保存用户补充。 */
  @PostMapping("/messages")
  public ApiResponse<ObjectNode> append(@PathVariable String jobId,@RequestBody ObjectNode input) {return ApiResponse.ok(jobs.append(jobId,input));}
  /** 人工核对总结会撤销旧方案的应用资格。 */
  @PutMapping("/summary")
  public ApiResponse<ObjectNode> summary(@PathVariable String jobId,@RequestBody ObjectNode input) {return ApiResponse.ok(jobs.summary(jobId,input));}
  /** 开发者确认后执行保存或保存并部署；运行权限另行重新核验。 */
  @PostMapping("/plans/{planId}/apply")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<ObjectNode> apply(@PathVariable String jobId,@PathVariable String planId,@RequestBody ObjectNode input,Principal actor) {
    return ApiResponse.ok(jobs.apply(jobId,planId,input,actor));
  }
  /** 网络响应不确定时核对原应用号，禁止重复保存与重启。 */
  @GetMapping("/applications/{requestId}")
  public ApiResponse<ObjectNode> application(@PathVariable String jobId,@PathVariable String requestId) {return ApiResponse.ok(jobs.application(jobId,requestId));}
}
