package com.flinkweb.service.network;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** 网络诊断契约，仅响应用户手动发起的单目标 TCP 检查。 */
public interface NetworkProbeService {
  /** 校验主机与端口，建立有界 TCP 连接并返回分层结果。 */
  ObjectNode test(String host, int port);
}
