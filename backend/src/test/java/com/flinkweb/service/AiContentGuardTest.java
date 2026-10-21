package com.flinkweb.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.security.permission.SecretCipher;
import com.flinkweb.service.ai.support.AiContentGuard;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 请求局部密钥快照保留逐叶脱敏结果并避免重复解密。 */
class AiContentGuardTest {
  /** 裸引用和嵌套叶子不被误判；引用后拼接明文赋值不能借此绕过检查。 */
  @Test
  void platformSecretReferencesSurviveLeafSanitization() throws Exception {
    var mapper=new ObjectMapper();var guard=new AiContentGuard(mock(RecordRepository.class),mock(SecretCipher.class));
    var input=mapper.readTree("{\"secret\":\"{{secret:stored-key}}\",\"nested\":[\"引用 {{secret:other-key}}\",\"{{config:runtime-sa}}\"]}");
    assertThat(guard.sanitize(input)).isEqualTo(input);
    assertThatCode(()->guard.requireSafe("{{secret:stored-key}}" )).doesNotThrowAnyException();
    assertThatThrownBy(()->guard.requireSafe("{{secret:stored-key}}password=plain-value")).hasMessageContaining("明文凭据");
    assertThatThrownBy(()->guard.requireSafe("password=plain-value")).hasMessageContaining("明文凭据");
  }
  /** 多字段只解密各密钥一次，后一次调用仍读取更新后的密钥。 */
  @Test
  void snapshotIsSharedOnlyWithinOneSanitizeCall() throws Exception {
    var mapper = new ObjectMapper();
    var records = mock(RecordRepository.class);
    var cipher = mock(SecretCipher.class);
    var config =
        mapper
            .createObjectNode()
            .put("id", "key")
            .put("kind", "secret")
            .put("encryptedValue", "encrypted-config");
    var model = mapper.createObjectNode().put("id", "model").put("encryptedKey", "encrypted-model");
    when(records.list("configs")).thenReturn(List.of(config));
    when(records.list("ai_models")).thenReturn(List.of(model));
    when(cipher.decrypt("key", "encrypted-config")).thenReturn("registered-config");
    when(cipher.decrypt("ai-model:model", "encrypted-model")).thenReturn("registered-model");
    var guard = new AiContentGuard(records, cipher);
    var input =
        mapper.readTree(
            "{\"one\":\"registered-config\",\"nested\":[\"registered-model\",{\"text\":\"password=plain"
                + " Bearer"
                + " abc.def\"}],\"reference\":\"password=${secret}\",\"null\":null,\"count\":1}");
    var expected =
        mapper.readTree(
            "{\"one\":\"[REDACTED]\",\"nested\":[\"[REDACTED]\",{\"text\":\"password=[REDACTED]"
                + " Bearer"
                + " [REDACTED]\"}],\"reference\":\"password=${secret}\",\"null\":null,\"count\":1}");
    clearInvocations(records, cipher);
    assertThat(guard.sanitize(input)).isEqualTo(expected);
    assertThat(input.path("one").asText()).isEqualTo("registered-config");
    verify(records, times(1)).list("configs");
    verify(records, times(1)).list("ai_models");
    verify(cipher, times(1)).decrypt("key", "encrypted-config");
    verify(cipher, times(1)).decrypt("ai-model:model", "encrypted-model");
    when(cipher.decrypt("key", "encrypted-config")).thenReturn("rotated-config");
    assertThat(
            guard
                .sanitize(mapper.createObjectNode().put("key", "rotated-config"))
                .path("key")
                .asText())
        .isEqualTo("[REDACTED]");
    assertThatThrownBy(() -> guard.requireSafe("registered-model")).hasMessageContaining("明文凭据");
  }
}
