package com.flinkweb.service.permission.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.audit.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/** 旧负责人和用户组不会隔离共享工作空间，也不能绕过角色。 */
class SharedWorkspaceAccessTest {
  /** 每项清理线程身份，避免污染后续校验。 */
  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
  }

  /** 角色对所有新旧资源一致生效，不读取旧归属权限作隔离。 */
  @ParameterizedTest
  @CsvSource({
    "ADMIN,true,true,true",
    "DEVELOPER,true,true,true",
    "OPERATOR,true,false,true",
    "VIEWER,true,false,false",
    "UNKNOWN,false,false,false"
  })
  void rolesApplyToEveryJob(String role, boolean read, boolean edit, boolean operate) {
    var records = mock(RecordRepository.class);
    var service = new AccessServiceImpl(records, new ObjectMapper(), mock(AuditService.class));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "other-account", "", AuthorityUtils.createAuthorityList("ROLE_" + role)));
    var evaluate = service.evaluator();
    for (String resource :
        java.util.List.of("other-owned-job", "sql:other-owned-sql", "legacy-job")) {
      assertThat(evaluate.test(resource, "read")).isEqualTo(read);
      assertThat(evaluate.test(resource, "edit")).isEqualTo(edit);
      assertThat(evaluate.test(resource, "operate")).isEqualTo(operate);
      assertThat(evaluate.test(resource, "unknown")).isFalse();
    }
    verifyNoInteractions(records);
  }
}
