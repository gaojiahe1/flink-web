package com.flinkweb.service.session;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.session.SessionRequest;
import java.util.List;

/** 普通 Session 与唯一 SQL 验证 Session 的配置、生命周期契约。 */
public interface SessionClusterService {
  /** 读取已登记 Session 配置。 */
  List<ObjectNode> list();

  /** 按修订号保存配置，验证 Session 通过固定主键保证唯一。 */
  ObjectNode save(String id, SessionRequest input);

  /** 刷新单个 Session 的真实状态。 */
  ObjectNode status(String id);

  /** 创建 Session 资源并返回受理状态。 */
  ObjectNode start(String id, long revision);

  /** 删除已核对身份的 Session 资源。 */
  ObjectNode stop(String id);

  /** 仅删除已经停止的登记。 */
  void delete(String id, long revision);

  /** 获取可用于 SQL 验证的专用配置。 */
  ObjectNode validationCluster();
}
