package com.flinkweb.service.catalog.support;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.security.permission.SecretCipher;
import java.util.HashMap;
import org.junit.jupiter.api.Test;

/** 验证环境密钥初始化不泄露、不覆盖，以及资源编号边界。 */
class S3CredentialDefaultsTest {
  /** 成对加密导入环境值，重启后保留已存在的管理员配置。 */
  @Test
  void seedsEncryptedPairOnce() {
    var records = mock(RecordRepository.class);
    var values = new HashMap<String, ObjectNode>();
    when(records.find(eq("configs"), anyString())).thenAnswer(i -> values.get(i.getArgument(1)));
    when(records.save(eq("configs"), anyString(), any(), eq(0L)))
        .thenAnswer(
            i -> {
              ObjectNode node = i.getArgument(2);
              values.put(i.getArgument(1), node);
              return node;
            });
    var cipher = new SecretCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    var mapper = new ObjectMapper();
    new S3CredentialDefaults(records, cipher, mapper, "fake-access", "fake-secret")
        .afterPropertiesSet();
    assertThat(values).hasSize(2);
    assertThat(values.toString()).doesNotContain("fake-access", "fake-secret");
    assertThat(
            cipher.decrypt(
                "s3-access-key", values.get("s3-access-key").path("encryptedValue").asText()))
        .isEqualTo("fake-access");
    assertThat(
            cipher.decrypt(
                "s3-secret-key", values.get("s3-secret-key").path("encryptedValue").asText()))
        .isEqualTo("fake-secret");
    new S3CredentialDefaults(records, cipher, mapper, "changed-access", "changed-secret")
        .afterPropertiesSet();
    verify(records, times(2)).save(eq("configs"), anyString(), any(), eq(0L));
  }

  /** 缺少凭据时不创建半套记录或读取外部工作负载凭据链。 */
  @Test
  void skipsIncompleteEnvironment() {
    var records = mock(RecordRepository.class);
    new S3CredentialDefaults(records, mock(SecretCipher.class), new ObjectMapper(), "access", "")
        .afterPropertiesSet();
    verifyNoInteractions(records);
  }

  /** 七位补零，大编号不截断，也不受默认区域设置影响。 */
  @Test
  void numberedNamesGrowWithoutTruncation() {
    assertThat(JobDraft.numberedName(1)).isEqualTo("flink-0000001");
    assertThat(JobDraft.numberedName(9999999)).isEqualTo("flink-9999999");
    assertThat(JobDraft.numberedName(10000000)).isEqualTo("flink-10000000");
    assertThat(JobDraft.numberedName(Long.MAX_VALUE)).isEqualTo("flink-9223372036854775807");
  }
}
