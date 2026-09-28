package com.flinkweb.service.permission.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.dto.permission.TransportChallenge;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.service.permission.SecretTransportService;
import jakarta.servlet.http.HttpSession;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.security.spec.MGF1ParameterSpec;
import java.time.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 用 RSA-OAEP-SHA256 封装随机 AES-256-GCM 密钥，挑战保存在当前会话且只消费一次。 */
@Service
public class SecretTransportServiceImpl implements SecretTransportService {
  private static final String ATTRIBUTE = SecretTransportServiceImpl.class.getName();
  private static final String ALGORITHM = "RSA-OAEP-256+A256GCM";
  private final KeyPair keys;
  private final ObjectMapper mapper;
  private final Clock clock;

  /** 保存有限的挑战元数据，不保存待传输密码或对称密钥。 */
  private record Pending(String purpose, String target, Instant expiresAt) {}

  /** 构建进程内临时密钥；重启后旧挑战失效，不写入元数据库。 */
  @Autowired
  public SecretTransportServiceImpl(ObjectMapper mapper) {
    this(mapper, Clock.systemUTC());
  }

  /** 允许测试通过固定时钟校验过期行为，生产使用系统 UTC 时钟。 */
  SecretTransportServiceImpl(ObjectMapper mapper, Clock clock) {
    this.mapper = mapper;
    this.clock = clock;
    try {
      var generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      keys = generator.generateKeyPair();
    } catch (GeneralSecurityException ex) {
      throw new IllegalStateException("Cannot initialize credential encryption");
    }
  }

  /** 签发 120 秒有效挑战；每个会话至多保留 8 项以支持多标签而不无限增长。 */
  @Override
  public TransportChallenge issue(HttpSession session, String purpose, String target) {
    if (!Set.of("login", "user-password", "config-secret").contains(purpose)
        || target == null
        || target.isBlank()
        || target.length() > 128) throw invalid();
    String id = UUID.randomUUID().toString();
    Instant expiry = clock.instant().plusSeconds(120);
    synchronized (session) {
      var pending = pending(session);
      pending.entrySet().removeIf(e -> !e.getValue().expiresAt().isAfter(clock.instant()));
      while (pending.size() >= 8) pending.remove(pending.keySet().iterator().next());
      pending.put(id, new Pending(purpose, target, expiry));
      session.setAttribute(ATTRIBUTE, pending);
    }
    return new TransportChallenge(
        id,
        ALGORITHM,
        Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()),
        purpose,
        target,
        expiry.toString());
  }

  /** 在认证或密钥持久化之前校验信封，所有失败使用统一错误避免泄露解密细节。 */
  @Override
  public String decrypt(HttpSession session, String purpose, String target, String envelope) {
    if (session == null
        || envelope == null
        || !envelope.startsWith("fwenc1:")
        || envelope.length() > 100000) throw invalid();
    byte[] key = null, clear = null;
    try {
      var data = mapper.readTree(envelope.substring(7));
      if (!data.isObject() || data.size() != 4) throw invalid();
      String id = data.path("id").asText();
      Pending challenge;
      synchronized (session) {
        var entries = pending(session);
        challenge = entries.remove(id);
        session.setAttribute(ATTRIBUTE, entries);
      }
      if (challenge == null
          || !challenge.expiresAt().isAfter(clock.instant())
          || !challenge.purpose().equals(purpose)
          || !challenge.target().equals(target)) throw invalid();
      byte[] wrapped = Base64.getDecoder().decode(data.path("key").asText());
      byte[] iv = Base64.getDecoder().decode(data.path("iv").asText());
      byte[] ciphertext = Base64.getDecoder().decode(data.path("data").asText());
      if (wrapped.length != 256
          || iv.length != 12
          || ciphertext.length < 16
          || ciphertext.length > 65552) throw invalid();
      var rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
      rsa.init(
          Cipher.DECRYPT_MODE,
          keys.getPrivate(),
          new OAEPParameterSpec(
              "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
      key = rsa.doFinal(wrapped);
      if (key.length != 32) throw invalid();
      var aes = Cipher.getInstance("AES/GCM/NoPadding");
      aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      aes.updateAAD((id + "\n" + purpose + "\n" + target).getBytes(StandardCharsets.UTF_8));
      clear = aes.doFinal(ciphertext);
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(clear))
          .toString();
    } catch (Exception ex) {
      throw invalid();
    } finally {
      if (key != null) Arrays.fill(key, (byte) 0);
      if (clear != null) Arrays.fill(clear, (byte) 0);
    }
  }

  /** 获取会话内挑战映射；调用者持有会话锁，禁止跨用户共享。 */
  @SuppressWarnings("unchecked")
  private LinkedHashMap<String, Pending> pending(HttpSession session) {
    Object value = session.getAttribute(ATTRIBUTE);
    return value instanceof LinkedHashMap<?, ?>
        ? (LinkedHashMap<String, Pending>) value
        : new LinkedHashMap<>();
  }

  /** 返回不含明文、密文和底层加密异常的可重试业务错误。 */
  private BusinessException invalid() {
    return new BusinessException(
        HttpStatus.BAD_REQUEST, "INVALID_CREDENTIAL_ENVELOPE", "密码或密钥加密校验失败，请重新提交");
  }
}
