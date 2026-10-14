package com.flinkweb.controller.ai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.service.ai.AiModelService;
import com.flinkweb.service.permission.SecretTransportService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 管理员维护模型，任务调用者只取得可用用途摘要。 */
@RestController
@RequestMapping("/api/ai/models")
public class AiModelController {
  private final AiModelService models;
  private final SecretTransportService transport;
  /** 注入模型服务与现有一次性密钥传输协议。 */
  public AiModelController(AiModelService models,SecretTransportService transport) { this.models=models;this.transport=transport; }
  /** 普通选择列表可用；管理详情额外检查管理员。 */
  @GetMapping
  public ApiResponse<List<ObjectNode>> list(@RequestParam(defaultValue="false") boolean management) { return ApiResponse.ok(models.list(management)); }
  /** 新密钥必须是绑定当前会话和模型标识的加密信封。 */
  @PutMapping("/{id}") @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<ObjectNode> save(@PathVariable String id,@RequestBody ObjectNode input,HttpServletRequest request) {
    var decoded=input.deepCopy();
    if (!input.path("apiKey").asText().isEmpty()) decoded.put("apiKey",transport.decrypt(request.getSession(false),"config-secret",id,input.path("apiKey").asText()));
    return ApiResponse.ok(models.save(id,decoded));
  }
  /** 删除模型需要当前修订号。 */
  @DeleteMapping("/{id}") @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<Void> delete(@PathVariable String id,@RequestParam long revision) { models.delete(id,revision);return ApiResponse.ok(null); }
  /** 管理员显式测试，不自动探测或发送业务上下文。 */
  @PostMapping("/{id}/test") @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<ObjectNode> test(@PathVariable String id) { return ApiResponse.ok(models.test(id)); }
}
