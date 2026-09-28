package com.flinkweb.service.permission;

import com.flinkweb.dto.permission.TransportChallenge;
import jakarta.servlet.http.HttpSession;

/** 密码和配置密钥的传输信封协议，独立于数据库密码哈希与静态加密。 */
public interface SecretTransportService {
  /** 签发当前会话、用途和目标绑定的短期一次性挑战。 */
  TransportChallenge issue(HttpSession session, String purpose, String target);

  /** 校验并消费挑战后解密，拒绝明文、重放、过期和跨目标使用。 */
  String decrypt(HttpSession session, String purpose, String target, String envelope);
}
