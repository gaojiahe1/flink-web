package com.flinkweb.controller.ai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.service.ai.AiHarnessService;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.util.List;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Harness 工作台入口；归属、生命周期及批准始终由服务重新核对。 */
@RestController
@RequestMapping("/api/ai/harness/sessions")
@PreAuthorize("hasAnyRole('ADMIN','DEVELOPER','OPERATOR')")
public class AiHarnessController {
  private final AiHarnessService harness;
  /** 控制器只依赖业务契约，不读取数据库或集群。 */
  public AiHarnessController(AiHarnessService harness){this.harness=harness;}
  /** 当前用户的私有会话列表。 */
  @GetMapping
  public ApiResponse<List<ObjectNode>> list(){return ApiResponse.ok(harness.list());}
  /** 创建独立会话，不自动调用模型。 */
  @PostMapping
  public ApiResponse<ObjectNode> create(@RequestBody ObjectNode input){return ApiResponse.ok(harness.create(input));}
  /** 恢复页面只读取元数据。 */
  @GetMapping("/{id}")
  public ApiResponse<ObjectNode> get(@PathVariable String id){return ApiResponse.ok(harness.get(id));}
  /** 删除私有会话及关联数据，未知提交须先核对。 */
  @DeleteMapping("/{id}")
  public ApiResponse<Void> delete(@PathVariable String id,@RequestParam long revision){harness.delete(id,revision);return ApiResponse.ok(null);}
  /** 计划及任务先持久化，再受理有界模型工具循环。 */
  @PostMapping("/{id}/turns")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public ApiResponse<ObjectNode> start(@PathVariable String id,@RequestBody ObjectNode input){return ApiResponse.ok(harness.start(id,input));}
  /** 只取消本轮后续执行，不撤销已提交运行操作。 */
  @PostMapping("/{id}/turns/{turnId}/cancel")
  public ApiResponse<ObjectNode> cancel(@PathVariable String id,@PathVariable String turnId){return ApiResponse.ok(harness.cancel(id,turnId));}
  /** 关闭代理缓冲，浏览器断线后可按原任务重取快照。 */
  @GetMapping(value="/{id}/turns/{turnId}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter events(@PathVariable String id,@PathVariable String turnId,HttpServletResponse response){response.setHeader("Cache-Control","no-store");response.setHeader("X-Accel-Buffering","no");return harness.events(id,turnId);}
  /** 修改参数或SQL候选仅允许具备开发角色的用户明确批准。 */
  @PostMapping("/{id}/turns/{turnId}/apply")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<ObjectNode> apply(@PathVariable String id,@PathVariable String turnId,@RequestBody ObjectNode input,Principal actor){return ApiResponse.ok(harness.apply(id,turnId,input,actor));}
  /** 手动核验原应用；不创建新的部署或后台观测。 */
  @PostMapping("/{id}/turns/{turnId}/observe")
  public ApiResponse<ObjectNode> observe(@PathVariable String id,@PathVariable String turnId){return ApiResponse.ok(harness.observe(id,turnId));}
}
