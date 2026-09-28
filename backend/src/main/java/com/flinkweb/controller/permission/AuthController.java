package com.flinkweb.controller.permission;

import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.dto.permission.CsrfResponse;
import com.flinkweb.dto.permission.CurrentUser;
import com.flinkweb.dto.permission.TransportChallenge;
import com.flinkweb.service.permission.SecretTransportService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 提供当前用户信息及 CSRF 令牌查询接口；登录和退出由安全过滤器处理。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {
  private final SecretTransportService transport;

  /** 注入短期传输挑战服务，认证状态仍交给 Spring Security 维护。 */
  public AuthController(SecretTransportService transport) {
    this.transport = transport;
  }

  /** 返回当前会话的一次性公钥挑战，明确禁止浏览器或代理缓存。 */
  @GetMapping("/transport-key")
  public ApiResponse<TransportChallenge> transportKey(
      @RequestParam String purpose,
      @RequestParam String target,
      HttpServletRequest request,
      HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    return ApiResponse.ok(transport.issue(request.getSession(), purpose, target));
  }

  /** 返回当前会话 CSRF 令牌及请求头名称。 */
  @GetMapping("/csrf")
  ApiResponse<CsrfResponse> csrf(CsrfToken token) {
    return ApiResponse.ok(new CsrfResponse(token.getHeaderName(), token.getToken()));
  }

  /** 返回已认证用户的用户名和角色。 */
  @GetMapping("/me")
  ApiResponse<CurrentUser> me(Authentication authentication) {
    return ApiResponse.ok(
        new CurrentUser(
            authentication.getName(),
            authentication.getAuthorities().stream().map(Object::toString).toList()));
  }
}
