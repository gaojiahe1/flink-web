package com.flinkweb.security.permission;

import com.flinkweb.service.permission.UserService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** 逐请求核验账号修订号，使停用、改密或改角色后的旧会话失效。 */
public class SessionRevisionFilter extends OncePerRequestFilter {
  private final UserService users;

  /** 注入用户服务，以便每次请求核对账号修订号。 */
  public SessionRevisionFilter(UserService users) {
    this.users = users;
  }

  /** 比较会话与账号当前修订号，失效时清除登录上下文及会话。 */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    var session = request.getSession(false);
    if (auth != null
        && auth.isAuthenticated()
        && !"anonymousUser".equals(auth.getName())
        && session != null) {
      Object expected = session.getAttribute("userRevision");
      long actual = users.revision(auth.getName());
      if (expected instanceof Long && (actual < 0 || !expected.equals(actual))) {
        session.invalidate();
        SecurityContextHolder.clearContext();
        response.setStatus(401);
        response.setContentType("application/json");
        response
            .getWriter()
            .write(
                "{\"data\":null,\"error\":{\"code\":\"SESSION_REVOKED\",\"message\":\"Account"
                    + " changed; sign in again\"}}");
        return;
      }
    }
    chain.doFilter(request, response);
  }
}
