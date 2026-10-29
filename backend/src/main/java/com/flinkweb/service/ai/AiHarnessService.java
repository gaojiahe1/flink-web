package com.flinkweb.service.ai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.util.List;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 私有 Harness 会话、流式运行、明确批准与关联清理的业务契约。 */
public interface AiHarnessService {
  /** 只列出本用户会话，不读取集群。 */
  List<ObjectNode> list();
  /** 幂等创建绑定资源生命周期的独立会话。 */
  ObjectNode create(ObjectNode input);
  /** 恢复本用户会话和有界近期执行记录。 */
  ObjectNode get(String id);
  /** 删除会话、任务、证据、方案及已结算应用；不删除业务草稿和审计。 */
  void delete(String id,long revision);
  /** 创建一轮有界任务，计划先于模型输出持久化。 */
  ObjectNode start(String id,ObjectNode input);
  /** 取消本轮后续模型和工具，不宣称撤销已提交动作。 */
  ObjectNode cancel(String id,String turnId);
  /** 通过真实执行事件投影当前任务；断开连接不等于取消。 */
  SseEmitter events(String id,String turnId);
  /** 批准具体方案后复用已有领域服务执行。 */
  ObjectNode apply(String id,String turnId,ObjectNode input,Principal actor);
  /** 手动核对原应用及运行结果，不重新部署。 */
  ObjectNode observe(String id,String turnId);
}
