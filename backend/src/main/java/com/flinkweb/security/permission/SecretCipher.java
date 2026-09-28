package com.flinkweb.security.permission;

import com.flinkweb.exception.common.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 使用外部主密钥与 AES-GCM 加解密敏感配置，密钥不随数据库持久化。 */
@Component
public class SecretCipher {
  private final String master;

  /** 保存外部提供的主密钥配置，供加解密时校验使用。 */
  public SecretCipher(@Value("${platform.security.master-key:}") String master) {
    this.master = master;
  }

  /** 解码并验证外部提供的 AES 主密钥。 */
  private SecretKeySpec key() {
    try {
      byte[] bytes = Base64.getDecoder().decode(master);
      if (bytes.length != 32) throw new IllegalArgumentException();
      return new SecretKeySpec(bytes, "AES");
    } catch (Exception ex) {
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE, "MASTER_KEY_MISSING", "请配置独立的 32 字节 Base64 主密钥");
    }
  }

  /** 使用随机 IV 进行 AES-GCM 加密，返回包含 IV 的密文。 */
  public String encrypt(String name, String value) {
    try {
      var key = key();
      byte[] iv = new byte[12];
      new SecureRandom().nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
      cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(iv)
          + "."
          + Base64.getEncoder()
              .encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException("Secret encryption failed");
    }
  }

  /** 解析密文并校验 GCM 认证标签后还原明文。 */
  public String decrypt(String name, String value) {
    try {
      var parts = value.split("\\.");
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.DECRYPT_MODE,
          key(),
          new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
      cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
      return new String(
          cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE, "SECRET_DECRYPT_FAILED", "密钥解密失败，请检查主密钥");
    }
  }
}
