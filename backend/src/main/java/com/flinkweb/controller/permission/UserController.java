package com.flinkweb.controller.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.service.permission.SecretTransportService;
import com.flinkweb.service.permission.UserService;
import jakarta.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 提供管理员用户维护接口，并声明管理员权限边界。 */
@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {
  private final UserService service;
  private final SecretTransportService transport;

  /** 注入用户维护业务服务。 */
  public UserController(UserService service, SecretTransportService transport) {
    this.service = service;
    this.transport = transport;
  }

  /** 查询已脱敏用户列表。 */
  @GetMapping
  public ApiResponse<List<ObjectNode>> list() {
    return ApiResponse.ok(service.list());
  }

  /** 新增或修改用户账号，由服务层校验密码、角色和修订号。 */
  @PutMapping("/{username}")
  public ApiResponse<ObjectNode> save(
      @PathVariable String username,
      @RequestBody ObjectNode input,
      Principal user,
      HttpServletRequest request) {
    var decoded = input.deepCopy();
    if (!input.path("password").asText("").isEmpty())
      decoded.put(
          "password",
          transport.decrypt(
              request.getSession(false),
              "user-password",
              username,
              input.path("password").asText()));
    return ApiResponse.ok(service.save(username, decoded, user.getName()));
  }

  /** 删除指定账号，保留对当前用户及最后一个管理员的保护。 */
  @DeleteMapping("/{username}")
  public ApiResponse<Void> delete(
      @PathVariable String username, @RequestParam long revision, Principal user) {
    service.delete(username, revision, user.getName());
    return ApiResponse.ok(null);
  }
}
