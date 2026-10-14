package com.flinkweb.service.ai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** 模型登记、用途筛选和受控凭据访问。 */
public interface AiModelService {
  /** 管理员查看脱敏配置，任务入口仅获得可用模型摘要。 */
  List<ObjectNode> list(boolean management);
  /** 管理员按修订保存模型，密钥由控制器解密后在服务中加密。 */
  ObjectNode save(String id, ObjectNode input);
  /** 删除指定修订的模型配置。 */
  void delete(String id, long revision);
  /** 取得允许该用途的启用模型，不返回给浏览器。 */
  ObjectNode require(String id, String purpose);
  /** 仅供服务调用读取解密后的模型凭据。 */
  String key(ObjectNode model);
  /** 管理员用固定无业务信息的消息测试已保存模型。 */
  ObjectNode test(String id);
}
