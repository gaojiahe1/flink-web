package com.flinkweb.service.permission.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.config.platform.PlatformProperties;
import com.flinkweb.config.platform.StartupFailureAnalyzer;
import com.flinkweb.exception.platform.StartupConfigurationException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.audit.AuditService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 验证初始密码的错误提示、摘要存储和既有用户在重启时不被改写。 */
class BootstrapAdminTest {
  /** 空密码和不合规字符/字节长度必须拒绝启动，诊断不得输出配置值。 */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"short", "一二三四五六七八九十一二三四五六七八九十一二三四五"})
  void invalidBootstrapPasswordHasActionableDiagnosis(String password) {
    var store = mock(RecordRepository.class);
    when(store.list("users")).thenReturn(List.of());
    var error = catchThrowable(() -> create(store, password));
    assertThat(error).isInstanceOf(StartupConfigurationException.class);
    var report = new StartupFailureAnalyzer().analyze(new IllegalStateException("wrapper", error));
    assertThat(report).isNotNull();
    assertThat(report.getDescription()).contains("FLINK_WEB_ADMIN_PASSWORD", "72 字节");
    assertThat(report.getAction()).contains("flink-web-secrets", "PVC");
    if (password != null && !password.isEmpty()) {
      assertThat(report.getDescription() + report.getAction()).doesNotContain(password);
    }
    verify(store, never()).save(anyString(), anyString(), any(), anyLong());
  }

  /** 合规密码只以 BCrypt 摘要创建管理员，用户名及角色仍使用原规则。 */
  @Test
  void validPasswordCreatesHashedAdmin() {
    var store = mock(RecordRepository.class);
    when(store.list("users")).thenReturn(List.of());
    create(store, "Bootstrap-password-test");
    var payload = ArgumentCaptor.forClass(ObjectNode.class);
    verify(store).save(eq("users"), eq("admin"), payload.capture(), eq(0L));
    assertThat(payload.getValue().path("role").asText()).isEqualTo("ADMIN");
    assertThat(payload.getValue().has("password")).isFalse();
    assertThat(
            new BCryptPasswordEncoder()
                .matches(
                    "Bootstrap-password-test", payload.getValue().path("passwordHash").asText()))
        .isTrue();
  }

  /** 已有用户时不再要求初始密码，不创建或覆盖管理员记录。 */
  @Test
  void existingUsersIgnoreBootstrapPassword() {
    var store = mock(RecordRepository.class);
    when(store.list("users"))
        .thenReturn(List.of(new ObjectMapper().createObjectNode().put("username", "admin")));
    create(store, "");
    verify(store, never()).save(anyString(), anyString(), any(), anyLong());
  }

  /** 用真实用户服务和隔离仓库构造启动流程，避免触碰实际环境数据。 */
  private UserServiceImpl create(RecordRepository store, String password) {
    return new UserServiceImpl(
        store,
        new PlatformProperties("test", null, new PlatformProperties.Security("admin", password)),
        new ObjectMapper(),
        mock(AuditService.class));
  }
}
