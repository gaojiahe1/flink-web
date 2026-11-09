package com.flinkweb.controller.ai;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.service.ai.support.AiApiTransport;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
/** 页面专用确认入口不注册为MCP工具，模型不能自行批准写请求。 */
@RestController @RequestMapping("/api/ai/api-proposals") @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','OPERATOR')")
public class AiApiProposalController {
  private final AiApiTransport transport;
  /** 复用私有提案状态和当前账号校验。 */
  public AiApiProposalController(AiApiTransport transport){this.transport=transport;}
  /** 用户读取冻结的实际方法、目标与请求参数。 */
  @GetMapping("/{id}") public ApiResponse<ObjectNode> get(@PathVariable String id){return ApiResponse.ok(transport.proposal(id));}
  /** 明确确认后只执行原请求一次，未知结果不自动重试。 */
  @PostMapping("/{id}/execute") public ApiResponse<ObjectNode> execute(@PathVariable String id,@RequestBody ObjectNode input){return ApiResponse.ok(transport.execute(id,input.path("confirmed").asBoolean()));}
}
