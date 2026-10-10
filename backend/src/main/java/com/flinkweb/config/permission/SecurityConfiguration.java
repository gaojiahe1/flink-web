package com.flinkweb.config.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.security.permission.EncryptedLoginFilter;
import com.flinkweb.security.permission.SessionRevisionFilter;
import com.flinkweb.service.permission.SecretTransportService;
import com.flinkweb.service.permission.UserService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** 配置会话认证、CSRF、防越权规则及 JSON 格式的认证结果。 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(SecurityConfiguration.class);

  /** 创建 BCrypt 密码编码器，用于密码存储与登录校验。 */
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  /** 组装认证、授权、CSRF 和会话失效过滤器，配置 JSON 登录及错误响应。 */
  @Bean
  SecurityFilterChain security(
      HttpSecurity http, ObjectMapper mapper, UserService users, SecretTransportService transport)
      throws Exception {
    http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/api/auth/csrf",
                        "/api/auth/login",
                        "/api/auth/transport-key",
                        "/actuator/health",
                        "/error")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(new SessionRevisionFilter(users), AuthorizationFilter.class)
        .addFilterBefore(
            new EncryptedLoginFilter(transport, mapper), UsernamePasswordAuthenticationFilter.class)
        .requestCache(cache -> cache.disable())
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (req, res, ex) -> {
                          log.warn(
                              "Authentication required method={} resource={}",
                              req.getMethod(),
                              safe(req.getRequestURI()),
                              ex);
                          write(mapper, res, 401, ApiResponse.fail("UNAUTHENTICATED", "请先登录"));
                        })
                    .accessDeniedHandler(
                        (req, res, ex) -> {
                          log.warn(
                              "Security access denied method={} resource={} actor={} errorType={}",
                              req.getMethod(),
                              safe(req.getRequestURI()),
                              req.getUserPrincipal() == null
                                  ? "anonymous"
                                  : safe(req.getUserPrincipal().getName()),
                              ex.getClass().getSimpleName(),
                              safeDenied(ex));
                          write(mapper, res, 403, ApiResponse.fail("FORBIDDEN", "请求无权限或安全令牌失效"));
                        }))
        .formLogin(
            form ->
                form.loginProcessingUrl("/api/auth/login")
                    .successHandler(
                        (req, res, auth) -> {
                          req.getSession()
                              .setAttribute("userRevision", users.revision(auth.getName()));
                          write(mapper, res, 200, ApiResponse.ok(auth.getName()));
                        })
                    .failureHandler(
                        (req, res, ex) ->
                            write(mapper, res, 401, ApiResponse.fail("LOGIN_FAILED", "用户名或密码不正确"))))
        .logout(
            logout ->
                logout
                    .logoutUrl("/api/auth/logout")
                    .logoutSuccessHandler(
                        (req, res, auth) -> write(mapper, res, 200, ApiResponse.ok("已退出"))));
    return http.build();
  }

  /** 以指定 HTTP 状态输出统一 JSON 响应。 */
  private static void write(
      ObjectMapper mapper, HttpServletResponse response, int status, Object body)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    mapper.writeValue(response.getWriter(), body);
  }

  /** 只记录路径和身份，去除换行并限制长度；不记录查询参数或安全令牌。 */
  private static String safe(String value) {
    String text = value.replaceAll("[\\r\\n\\t]", "_");
    return text.substring(0, Math.min(text.length(), 512));
  }

  /** CSRF 异常消息会带入实际令牌，遮蔽令牌但保留异常类型、全部栈帧与原因链。 */
  private static org.springframework.security.access.AccessDeniedException safeDenied(
      org.springframework.security.access.AccessDeniedException failure) {
    org.springframework.security.access.AccessDeniedException copy;
    if (failure instanceof org.springframework.security.web.csrf.InvalidCsrfTokenException) {
      copy =
          new org.springframework.security.web.csrf.InvalidCsrfTokenException(
              new org.springframework.security.web.csrf.DefaultCsrfToken(
                  "X-CSRF-TOKEN", "_csrf", "[REDACTED]"),
              "[REDACTED]");
    } else if (failure instanceof org.springframework.security.web.csrf.MissingCsrfTokenException) {
      copy = new org.springframework.security.web.csrf.MissingCsrfTokenException("[REDACTED]");
    } else return failure;
    copy.setStackTrace(failure.getStackTrace());
    if (failure.getCause() != null) copy.initCause(failure.getCause());
    for (Throwable suppressed : failure.getSuppressed()) copy.addSuppressed(suppressed);
    return copy;
  }
}
