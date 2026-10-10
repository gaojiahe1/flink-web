package com.flinkweb.client.flink;

import static org.assertj.core.api.Assertions.*;

import com.flinkweb.exception.common.BusinessException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** 验证 Flink 完整日志响应的固定内存尾部读取和总大小保护。 */
class SessionLogTailTest {
  /** 大文件只返回尾部，中文内容和环形缓冲的跨段顺序保持正确。 */
  @Test
  void fullLogReturnsOrderedUtf8Tail() throws Exception {
    String tail = "FWV_01234567890123456789012345678901_0> +I[验证, 100]\n";
    byte[] content = ("x".repeat(650000) + tail).getBytes(StandardCharsets.UTF_8);
    var result = SessionRestClient.readTail(new ByteArrayInputStream(content), 524288, 1024 * 1024);
    assertThat(result).endsWith(tail);
    assertThat(result.getBytes(StandardCharsets.UTF_8)).hasSize(524288);
    assertThat(
            SessionRestClient.readTail(
                new ByteArrayInputStream(tail.getBytes(StandardCharsets.UTF_8)),
                524288,
                1024 * 1024))
        .isEqualTo(tail);
  }

  /** 超过总传输限额拒绝继续读取，避免上游无限日志占用请求。 */
  @Test
  void oversizedLogIsRejected() {
    assertThatThrownBy(
            () -> SessionRestClient.readTail(new ByteArrayInputStream(new byte[100]), 16, 64))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("日志超过");
  }
}
