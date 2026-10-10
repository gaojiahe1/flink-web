package com.flinkweb.client.storage;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;

/** 验证 S3 诊断副本保留完整异常关系和原始栈帧，同时遮蔽签名及上游正文。 */
class S3FailureDiagnosticsTest {
  /** 多层原因及 suppressed 异常全部保留；原始对象及其调用栈不被改写。 */
  @Test
  void keepsEveryCauseAndSuppressedStackWhileMaskingCredentials() {
    var root =
        new IOException(
            "TLS failed token=private-token at"
                + " https://user:pass@store.test/key?signature=private-signature");
    var suppressed = new IOException("stream cleanup secret=private-secret");
    root.addSuppressed(suppressed);
    var sdk = SdkClientException.builder().message("upstream-sensitive-body").cause(root).build();
    var outer = new IllegalStateException("storage retry failed", sdk);
    var copy = S3FailureDiagnostics.trace(outer);
    assertThat(copy.getStackTrace()).containsExactly(outer.getStackTrace());
    assertThat(copy.getCause().getStackTrace()).containsExactly(sdk.getStackTrace());
    assertThat(copy.getCause().getCause().getStackTrace()).containsExactly(root.getStackTrace());
    assertThat(copy.getCause().getCause().getSuppressed()).hasSize(1);
    assertThat(copy.getCause().getCause().getSuppressed()[0].getStackTrace())
        .containsExactly(suppressed.getStackTrace());
    String text =
        ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(
            new ch.qos.logback.classic.spi.ThrowableProxy(copy));
    assertThat(text)
        .contains(
            "SdkClientException",
            "java.io.IOException",
            "TLS failed",
            "Caused by:",
            "Suppressed:",
            "[REDACTED]")
        .doesNotContain(
            "upstream-sensitive-body",
            "private-token",
            "private-signature",
            "private-secret",
            "user:pass");
    assertThat(root.getMessage()).contains("private-token");
  }

  /** 异常原因存在循环引用时可安全复制，保持原始关系，不以固定层数截断。 */
  @Test
  void retainsCircularCauseRelationshipWithoutRecursionFailure() {
    var first = new IOException("first");
    var second = new IOException("second");
    first.initCause(second);
    second.initCause(first);
    var copy = S3FailureDiagnostics.trace(first);
    assertThat(copy.getCause().getCause()).isSameAs(copy);
  }
}
