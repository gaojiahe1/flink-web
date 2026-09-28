package com.flinkweb.service.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.security.core.userdetails.*;

/** 服务契约：管理账号、密码和角色，提供认证查询、管理员保护及会话版本控制。 */
public interface UserService
    extends org.springframework.security.core.userdetails.UserDetailsService {
  /** 读取启用账号并转换为 Spring Security 认证用户。 */
  UserDetails loadUserByUsername(String username);

  /** 返回当前账号修订号，供会话失效检查使用。 */
  long revision(String username);

  /** 查询用户列表并移除密码摘要。 */
  List<ObjectNode> list();

  /** 校验用户名、密码、角色和管理员约束后保存账号并增加修订号。 */
  ObjectNode save(String username, ObjectNode input, String actor);

  /** 禁止删除当前登录的管理员，随后按修订号删除目标账号并记录审计。 */
  void delete(String username, long revision, String actor);
}
