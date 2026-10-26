package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.security.permission.SecretCipher;
import com.flinkweb.service.ai.support.AiContentGuard;
import com.flinkweb.service.ai.support.AiEvidenceDocuments;
import com.flinkweb.service.job.creation.YamlService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 对部署SQL原文和文档脱敏同时执行严格Base64/UTF-8边界验证。 */
class AiEvidenceDocumentsTest {
  final ObjectMapper mapper = new ObjectMapper();

  /** 使用真实脱敏逻辑，密钥存储为空；不把有效SQL伪造成损坏文本。 */
  AiEvidenceDocuments documents() {
    var records = mock(RecordRepository.class);
    when(records.list(anyString())).thenReturn(List.of());
    return new AiEvidenceDocuments(mock(YamlService.class), new AiContentGuard(records, mock(SecretCipher.class)));
  }

  /** 为单条已编码SQL构造实际部署参数。 */
  ObjectNode source(String encoded) {
    var value = mapper.createObjectNode();
    value.withObject("/spec/job").putArray("args").add("--statement").add(encoded); return value;
  }

  /** 合法中文和引号保持原文语义，密码在SQL正文和重编码参数中都隐藏。 */
  @Test void validChineseRemainsReadableAndSecretsAreScrubbed() {
    String sql = "SELECT '中文😀'; -- password=private-value";
    String encoded = Base64.getEncoder().encodeToString(sql.getBytes(StandardCharsets.UTF_8));
    var docs = documents(); var input = source(encoded);
    assertThat(docs.sql(input)).contains("中文😀", "[REDACTED]").doesNotContain("private-value");
    String clean = docs.resource(input).at("/spec/job/args/1").asText();
    assertThat(new String(Base64.getDecoder().decode(clean), StandardCharsets.UTF_8)).contains("中文😀", "[REDACTED]").doesNotContain("private-value");
    assertThat(input.at("/spec/job/args/1").asText()).isEqualTo(encoded);
  }

  /** 非法Base64、截断、无效续字节、过长编码和代理项字节不能进入原文核对。 */
  @Test void invalidEncodingsAreUnreadableInBothProjections() {
    var values = new ArrayList<String>(); values.add("%%%not-base64");
    for (byte[] bytes : List.of(new byte[] {(byte)0xc3, 0x28}, new byte[] {(byte)0xe4, (byte)0xb8}, new byte[] {(byte)0xc0, (byte)0xaf}, new byte[] {(byte)0xed, (byte)0xa0, (byte)0x80})) values.add(Base64.getEncoder().encodeToString(bytes));
    for (String value : values) {
      var docs = documents(); var input = source(value);
      assertThat(docs.sql(input)).contains("不是有效的SQL编码").doesNotContain("\ufffd");
      assertThat(docs.resource(input).at("/spec/job/args/1").asText()).isEqualTo("[UNREADABLE SQL]");
    }
  }

  /** 本来就合法编码的替换字符是实际原文，不与错误字节解码混淆。 */
  @Test void genuineReplacementCharacterIsValidText() {
    String sql = "SELECT '\ufffd'";
    assertThat(documents().sql(source(Base64.getEncoder().encodeToString(sql.getBytes(StandardCharsets.UTF_8))))).isEqualTo(sql);
  }
}
