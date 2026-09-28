package com.flinkweb.service.permission.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.config.platform.PlatformProperties;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.audit.AuditService;
import com.flinkweb.service.permission.UserService;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理账号、密码和角色，提供认证查询、管理员保护及会话版本控制。 */
@Service
public class UserServiceImpl implements UserService {
  private final RecordRepository store;
  private final AuditService audit;
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

  /** 注入账号存储及审计依赖；空用户库首次启动时创建配置的管理员。 */
  public UserServiceImpl(
      RecordRepository store,
      PlatformProperties properties,
      ObjectMapper mapper,
      AuditService audit) {
    this.store = store;
    this.audit = audit;
    if (store.list("users").isEmpty()) {
      String password = properties.security().password();
      checkPassword(password);
      var admin =
          mapper
              .createObjectNode()
              .put("username", properties.security().username())
              .put("passwordHash", encoder.encode(password))
              .put("role", "ADMIN")
              .put("enabled", true);
      store.save("users", properties.security().username(), admin, 0);
    }
  }

  /** 读取启用账号并转换为 Spring Security 认证用户。 */
  @Override
  public UserDetails loadUserByUsername(String username) {
    var user = store.find("users", username);
    if (user == null) throw new UsernameNotFoundException("Unknown user");
    return User.withUsername(username)
        .password(user.path("passwordHash").asText())
        .roles(user.path("role").asText())
        .disabled(!user.path("enabled").asBoolean())
        .build();
  }

  /** 返回当前账号修订号，供会话失效检查使用。 */
  public long revision(String username) {
    var user = store.find("users", username);
    return user == null ? -1 : user.path("revision").asLong();
  }

  /** 查询用户列表并移除密码摘要。 */
  public List<ObjectNode> list() {
    return store.list("users").stream().map(this::redact).toList();
  }

  /** 复制账号记录并删除密码摘要等内部认证数据。 */
  private ObjectNode redact(ObjectNode node) {
    var copy = node.deepCopy();
    copy.remove("passwordHash");
    return copy;
  }

  /** 校验用户名、密码、角色和管理员约束后保存账号并增加修订号。 */
  @Transactional
  public ObjectNode save(String username, ObjectNode input, String actor) {
    if (!username.matches("[a-zA-Z0-9_.-]{3,64}")) fail("用户名需为 3-64 位字母、数字、点、下划线或连字符");
    String role = input.path("role").asText();
    if (!Set.of("ADMIN", "DEVELOPER", "OPERATOR", "VIEWER").contains(role)) fail("角色无效");
    if (username.equals(actor) && (!role.equals("ADMIN") || !input.path("enabled").asBoolean(true)))
      fail("不能降低当前管理员自身权限或禁用自身");
    var old = store.find("users", username);
    var data =
        input
            .objectNode()
            .put("username", username)
            .put("role", role)
            .put("enabled", input.path("enabled").asBoolean(true));
    String password = input.path("password").asText("");
    if (old == null || !password.isEmpty()) {
      checkPassword(password);
      data.put("passwordHash", encoder.encode(password));
    } else data.put("passwordHash", old.path("passwordHash").asText());
    var saved = store.save("users", username, data, input.path("revision").asLong());
    audit.record("USER_SAVE", username, "SUCCESS");
    return redact(saved);
  }

  /** 禁止删除当前登录的管理员，随后按修订号删除目标账号并记录审计。 */
  @Transactional
  public void delete(String username, long revision, String actor) {
    if (username.equals(actor)) fail("不能删除当前管理员");
    store.delete("users", username, revision);
    audit.record("USER_DELETE", username, "SUCCESS");
  }

  /** 验证密码是否达到平台最小长度要求。 */
  private void checkPassword(String password) {
    if (password == null
        || password.length() < 12
        || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      fail("密码至少 12 个字符，UTF-8 编码后最多 72 字节");
  }

  /** 抛出用户维护参数不合法的业务异常。 */
  private void fail(String message) {
    throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_USER", message);
  }
}
