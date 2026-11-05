package com.flinkweb.service.ai;
import com.fasterxml.jackson.databind.node.ObjectNode;
/** 已登录用户的无状态MCP请求和私有偏好契约。 */
public interface AiHarnessMcpService {
  /** 分发JSON-RPC请求；通知无响应，领域错误转换为安全协议结果。 */
  ObjectNode dispatch(ObjectNode request,boolean allowLogs);
  /** 读取当前账号偏好。 */
  ObjectNode preferences();
}
