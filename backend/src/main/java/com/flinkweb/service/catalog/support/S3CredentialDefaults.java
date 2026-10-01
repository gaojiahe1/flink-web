package com.flinkweb.service.catalog.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.security.permission.SecretCipher;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 将显式环境凭据一次性加密导入配置管理，不覆盖用户维护的配置。 */
@Component
public class S3CredentialDefaults implements InitializingBean {
  private final RecordRepository records;
  private final SecretCipher cipher;
  private final ObjectMapper mapper;
  private final String accessKey, secretKey;

  /** 注入存储和加密能力，仅读取显式的环境凭据，不固化临时工作负载令牌。 */
  public S3CredentialDefaults(
      RecordRepository records,
      SecretCipher cipher,
      ObjectMapper mapper,
      @Value("${AWS_ACCESS_KEY_ID:}") String accessKey,
      @Value("${AWS_SECRET_ACCESS_KEY:}") String secretKey) {
    this.records = records;
    this.cipher = cipher;
    this.mapper = mapper;
    this.accessKey = accessKey;
    this.secretKey = secretKey;
  }

  /** 初始化成对的配置项，缺少环境值时由管理员在配置管理中补充。 */
  @Override
  public void afterPropertiesSet() {
    if (accessKey.isBlank() || secretKey.isBlank()) return;
    seed("s3-access-key", "S3 Access Key", accessKey);
    seed("s3-secret-key", "S3 Secret Key", secretKey);
  }

  /** 只新增不存在的密钥，保存前加密，禁止明文进入元数据。 */
  private void seed(String id, String name, String value) {
    if (records.find("configs", id) != null) return;
    ObjectNode entry =
        mapper
            .createObjectNode()
            .put("name", name)
            .put("kind", "secret")
            .put("description", "从平台 S3 环境变量初始化；后续在配置管理中维护")
            .put("encryptedValue", cipher.encrypt(id, value));
    records.save("configs", id, entry, 0);
  }
}
