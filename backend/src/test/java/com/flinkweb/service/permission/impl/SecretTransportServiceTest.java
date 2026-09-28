package com.flinkweb.service.permission.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.CredentialEnvelopeFixture;
import com.flinkweb.exception.common.BusinessException;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

/** 覆盖密码协议的会话、用途、目标、过期、并发与篡改边界。 */
class SecretTransportServiceTest {
  /** 不同会话不能消费挑战；成功解密后不能重放。 */
  @Test
  void sessionBindingAndSingleUse() throws Exception {
    var service = new SecretTransportServiceImpl(new ObjectMapper());
    var session = new MockHttpSession();
    var cipher =
        CredentialEnvelopeFixture.encrypt(service.issue(session, "login", "alice"), "secret-你好");
    assertThatThrownBy(() -> service.decrypt(new MockHttpSession(), "login", "alice", cipher))
        .isInstanceOf(BusinessException.class);
    assertThat(service.decrypt(session, "login", "alice", cipher)).isEqualTo("secret-你好");
    assertThatThrownBy(() -> service.decrypt(session, "login", "alice", cipher))
        .isInstanceOf(BusinessException.class);
  }

  /** 超时、跨用途、跨目标和损坏密文不允许降级为明文。 */
  @Test
  void expiryPurposeTargetAndTamper() throws Exception {
    var clock = mock(Clock.class);
    Instant now = Instant.parse("2026-09-28T00:00:00Z");
    when(clock.instant()).thenReturn(now);
    var mapper = new ObjectMapper();
    var service = new SecretTransportServiceImpl(mapper, clock);
    var session = new MockHttpSession();
    var expired =
        CredentialEnvelopeFixture.encrypt(
            service.issue(session, "login", "alice"), "valid-password");
    when(clock.instant()).thenReturn(now.plusSeconds(121));
    assertThatThrownBy(() -> service.decrypt(session, "login", "alice", expired))
        .isInstanceOf(BusinessException.class);
    var wrongPurpose =
        CredentialEnvelopeFixture.encrypt(
            service.issue(session, "user-password", "alice"), "valid-password");
    assertThatThrownBy(() -> service.decrypt(session, "login", "alice", wrongPurpose))
        .isInstanceOf(BusinessException.class);
    var wrongTarget =
        CredentialEnvelopeFixture.encrypt(
            service.issue(session, "login", "alice"), "valid-password");
    assertThatThrownBy(() -> service.decrypt(session, "login", "bob", wrongTarget))
        .isInstanceOf(BusinessException.class);
    var valid =
        CredentialEnvelopeFixture.encrypt(
            service.issue(session, "login", "alice"), "valid-password");
    var data = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(valid.substring(7));
    byte[] bytes = java.util.Base64.getDecoder().decode(data.path("data").asText());
    bytes[0] ^= 1;
    data.put("data", java.util.Base64.getEncoder().encodeToString(bytes));
    assertThatThrownBy(() -> service.decrypt(session, "login", "alice", "fwenc1:" + data))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.decrypt(session, "login", "alice", "plaintext"))
        .isInstanceOf(BusinessException.class);
  }

  /** 同一会话支持多个未完成挑战但有容量限制，避免标签页互相覆盖及无限积累。 */
  @Test
  void boundedMultiTabChallenges() throws Exception {
    var service = new SecretTransportServiceImpl(new ObjectMapper());
    var session = new MockHttpSession();
    var oldest =
        CredentialEnvelopeFixture.encrypt(service.issue(session, "login", "alice"), "password");
    for (int i = 0; i < 8; i++) service.issue(session, "login", "alice");
    assertThatThrownBy(() -> service.decrypt(session, "login", "alice", oldest))
        .isInstanceOf(BusinessException.class);
    var a = CredentialEnvelopeFixture.encrypt(service.issue(session, "login", "alice"), "first");
    var b = CredentialEnvelopeFixture.encrypt(service.issue(session, "login", "alice"), "second");
    assertThat(service.decrypt(session, "login", "alice", a)).isEqualTo("first");
    assertThat(service.decrypt(session, "login", "alice", b)).isEqualTo("second");
  }
}
