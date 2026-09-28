package com.flinkweb.controller.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.service.permission.AccessService;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 用户组维护接口。 */
@RestController
@RequestMapping("/api")
public class GroupController {
  private final AccessService access;

  /** 注入本模块接口依赖，不在控制器内执行持久化逻辑。 */
  public GroupController(AccessService access) {
    this.access = access;
  }

  /** 查询用户组，普通用户仅看到自己的组。 */
  @GetMapping("/platform/groups")
  public ApiResponse<List<ObjectNode>> groups() {
    return ApiResponse.ok(access.groups());
  }

  /** 管理员登记用户组。 */
  @PutMapping("/platform/groups/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<ObjectNode> group(@PathVariable String id, @RequestBody ObjectNode input) {
    return ApiResponse.ok(access.saveGroup(id, input));
  }
}
