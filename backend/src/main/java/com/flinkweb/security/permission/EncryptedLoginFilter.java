package com.flinkweb.security.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.service.permission.SecretTransportService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.web.filter.OncePerRequestFilter;

/** 仅在 CSRF 检查之后解密登录密码，再交给原 Spring Security 认证及会话流程。 */
public class EncryptedLoginFilter extends OncePerRequestFilter {
  private final SecretTransportService transport;
  private final ObjectMapper mapper;

  /** 注入信封校验器及统一 JSON 输出依赖，不注册额外的 Servlet 过滤器。 */
  public EncryptedLoginFilter(SecretTransportService transport, ObjectMapper mapper) {
    this.transport = transport;
    this.mapper = mapper;
  }

  /** 只处理精确的 POST 登录端点；其他请求沿用现有安全链。 */
  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equals(request.getMethod())
        || !"/api/auth/login"
            .equals(request.getRequestURI().substring(request.getContextPath().length()));
  }

  /** 校验并解密密码参数，失败时不进入用户名密码认证且不记录敏感数据。 */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String plain;
    try {
      plain =
          transport.decrypt(
              request.getSession(false),
              "login",
              request.getParameter("username"),
              request.getParameter("password"));
    } catch (BusinessException ex) {
      response.setStatus(ex.status().value());
      response.setContentType("application/json");
      response.setCharacterEncoding("UTF-8");
      response.setHeader("Cache-Control", "no-store");
      mapper.writeValue(response.getWriter(), ApiResponse.fail(ex.code(), ex.getMessage()));
      return;
    }
    chain.doFilter(new PasswordRequest(request, plain), response);
  }

  /** 只替换交给认证器的参数视图，不修改原请求体，不缓存解密后的凭据。 */
  private static class PasswordRequest extends HttpServletRequestWrapper {
    private final String password;

    /** 保留请求所有原行为并持有本次认证所需的临时明文。 */
    PasswordRequest(HttpServletRequest request, String password) {
      super(request);
      this.password = password;
    }

    /** 为认证器返回解密后的密码，其余参数保持不变。 */
    @Override
    public String getParameter(String name) {
      return "password".equals(name) ? password : super.getParameter(name);
    }

    /** 保证参数数组视图与单值视图一致。 */
    @Override
    public String[] getParameterValues(String name) {
      return "password".equals(name) ? new String[] {password} : super.getParameterValues(name);
    }

    /** 返回不可变参数映射，避免认证后的组件读到不一致的参数。 */
    @Override
    public Map<String, String[]> getParameterMap() {
      var values = new HashMap<>(super.getParameterMap());
      values.put("password", new String[] {password});
      return Collections.unmodifiableMap(values);
    }
  }
}
