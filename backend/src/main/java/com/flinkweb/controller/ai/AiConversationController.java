package com.flinkweb.controller.ai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.service.ai.AiConversationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** SQL 草稿的私有持久化对话；不由浏览器提交模型回复或历史列表。 */
@RestController
@RequestMapping("/api/ai/conversations/{sqlId}")
@PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
public class AiConversationController {
  private final AiConversationService conversations;
  /** 注入会话契约，控制层不直接访问元数据。 */
  public AiConversationController(AiConversationService conversations) { this.conversations = conversations; }
  /** 按当前身份恢复会话。 */
  @GetMapping
  public ApiResponse<ObjectNode> get(@PathVariable String sqlId) { return ApiResponse.ok(conversations.get(sqlId)); }
  /** 追加一条幂等用户消息。 */
  @PostMapping("/messages")
  public ApiResponse<ObjectNode> append(@PathVariable String sqlId, @RequestBody ObjectNode input) {
    return ApiResponse.ok(conversations.append(sqlId, input));
  }
  /** 保存人工核对后的需求总结。 */
  @PutMapping("/summary")
  public ApiResponse<ObjectNode> summary(@PathVariable String sqlId, @RequestBody ObjectNode input) {
    return ApiResponse.ok(conversations.summary(sqlId, input));
  }
  /** 显式确认总结与当前 SQL，生成动作仍须主动发起。 */
  @PostMapping("/confirm")
  public ApiResponse<ObjectNode> confirm(@PathVariable String sqlId, @RequestBody ObjectNode input) {
    return ApiResponse.ok(conversations.confirm(sqlId, input));
  }
}
