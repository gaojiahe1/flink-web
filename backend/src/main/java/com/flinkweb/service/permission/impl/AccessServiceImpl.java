package com.flinkweb.service.permission.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.audit.AuditService;
import com.flinkweb.service.permission.AccessService;
import java.net.URI;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** 管理作业负责人、业务归属及用户组范围权限；平台角色仍由接口层限制。 */
@Service
public class AccessServiceImpl implements AccessService {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(AccessServiceImpl.class);
  private final RecordRepository records;
  private final ObjectMapper mapper;
  private final AuditService audit;

  /** 注入元数据与审计依赖，不访问 Kubernetes。 */
  public AccessServiceImpl(RecordRepository records, ObjectMapper mapper, AuditService audit) {
    this.records = records;
    this.mapper = mapper;
    this.audit = audit;
  }

  /** 获取当前身份，供版本与归属记录使用。 */
  public String actor() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    return auth == null ? "system" : auth.getName();
  }

  /** 判断当前账号是否拥有管理员角色。 */
  public boolean admin() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null
        && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
  }

  /** 单工作空间内作业共享可见，操作范围只取决于当前平台角色。 */
  public boolean allowed(String resource, String action) {
    return evaluator().test(resource, action);
  }

  /** 每次请求固定角色快照；历史负责人/用户组保留为元数据，不再隔离作业。 */
  public java.util.function.BiPredicate<String, String> evaluator() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    Set<String> roles = new HashSet<>();
    if (auth != null && auth.isAuthenticated())
      auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
    boolean read = roles.stream().anyMatch(Set.of("ROLE_ADMIN", "ROLE_DEVELOPER", "ROLE_OPERATOR", "ROLE_VIEWER")::contains);
    boolean edit = roles.contains("ROLE_ADMIN") || roles.contains("ROLE_DEVELOPER");
    boolean operate = edit || roles.contains("ROLE_OPERATOR");
    return (resource, action) -> switch (action) {
      case "read" -> read;
      case "edit" -> edit;
      case "operate" -> operate;
      default -> false;
    };
  }

  /** 对直接 API 调用执行与列表一致的范围校验。 */
  public void require(String resource, String action) {
    if (!allowed(resource, action)) {
      var failure =
          new BusinessException(HttpStatus.FORBIDDEN, "RESOURCE_FORBIDDEN", "没有该作业的访问或操作权限");
      log.warn(
          "Resource access denied actor={} resource={} action={}",
          actor().replaceAll("[\\r\\n\\t]", "_"),
          resource.replaceAll("[\\r\\n\\t]", "_"),
          action,
          failure);
      throw failure;
    }
  }

  /** 新建作业时登记负责人；同事务调用避免产生无归属的半成品。 */
  public void initialize(String resource) {
    if (records.find("access", resource) == null)
      records.save(
          "access",
          resource,
          mapper.createObjectNode().put("owner", actor()).put("project", ""),
          0);
  }

  /** SQL 首次生成普通作业时复制访问范围，保持两个入口一致。 */
  public void copy(String from, String to) {
    var source = records.find("access", from);
    if (source == null) {
      initialize(to);
      return;
    }
    var target = records.find("access", to);
    records.save("access", to, source, target == null ? 0 : target.path("revision").asLong());
  }

  /** 返回授权规则和当前用户允许的操作，用于页面禁用入口。 */
  public ObjectNode get(String resource) {
    require(resource, "read");
    var node = records.find("access", resource);
    if (node == null)
      node = mapper.createObjectNode().put("revision", 0).put("owner", "").put("project", "");
    node.put("canEdit", allowed(resource, "edit"))
        .put("canOperate", allowed(resource, "operate"))
        .put("canManage", admin() || actor().equals(node.path("owner").asText()));
    return node;
  }

  /** 仅管理员或当前负责人可调整归属；校验成员组和外部监控链接。 */
  public ObjectNode save(String resource, ObjectNode input) {
    var old = records.find("access", resource);
    if (!admin() && (old == null || !actor().equals(old.path("owner").asText()))) deny();
    String owner = input.path("owner").asText();
    if (records.find("users", owner) == null) fail("负责人必须是已注册用户");
    var node =
        mapper
            .createObjectNode()
            .put("owner", owner)
            .put("project", input.path("project").asText(""));
    if (node.path("project").asText().length() > 100) fail("业务标签最长 100 字符");
    for (String field : List.of("readGroups", "operateGroups", "editGroups")) {
      var list = node.putArray(field);
      if (input.has(field) && !input.path(field).isArray()) fail("用户组必须为数组");
      if (input.path(field).size() > 50) fail("用户组过多");
      for (JsonNode group : input.path(field)) {
        records.require("groups", group.asText());
        list.add(group.asText());
      }
    }
    var links = node.putArray("externalLinks");
    if (input.has("externalLinks") && !input.path("externalLinks").isArray()) fail("外部链接必须为数组");
    if (input.path("externalLinks").size() > 10) fail("最多配置 10 个外部链接");
    for (JsonNode link : input.path("externalLinks")) {
      String url = link.path("url").asText(), name = link.path("name").asText();
      try {
        var uri = URI.create(url);
        if (!Set.of("http", "https").contains(uri.getScheme())
            || uri.getHost() == null
            || uri.getUserInfo() != null
            || url.length() > 2048
            || name.isBlank()
            || name.length() > 60) fail("外部链接必须为 HTTP(S) 地址并填写名称");
      } catch (IllegalArgumentException ex) {
        fail("外部链接格式无效");
      }
      links.addObject().put("name", name).put("url", url);
    }
    var saved = records.save("access", resource, node, input.path("revision").asLong());
    audit.record("ACCESS_UPDATE", resource, "SUCCESS");
    return saved;
  }

  /** 查询用户组；非管理员仅可查看所属组。 */
  public List<ObjectNode> groups() {
    return records.list("groups").stream()
        .filter(
            g ->
                admin()
                    || java.util.stream.StreamSupport.stream(g.path("members").spliterator(), false)
                        .anyMatch(m -> m.asText().equals(actor())))
        .toList();
  }

  /** 管理员维护用户组，成员必须为现有账号。 */
  public ObjectNode saveGroup(String id, ObjectNode input) {
    if (!admin()) deny();
    if (!id.matches("[a-z0-9][a-z0-9_.-]{0,100}")) fail("用户组标识无效");
    if (!input.path("members").isArray() || input.path("members").size() > 200)
      fail("成员必须为最多 200 项的数组");
    ObjectNode node = mapper.createObjectNode().put("name", input.path("name").asText(id));
    var members = node.putArray("members");
    for (JsonNode member : input.path("members")) {
      records.require("users", member.asText());
      members.add(member.asText());
    }
    audit.record("GROUP_SAVE", id, "SUCCESS");
    return records.save("groups", id, node, input.path("revision").asLong());
  }

  /** 输出授权错误。 */
  private void deny() {
    throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "仅管理员或负责人可管理作业归属");
  }

  /** 输出字段校验错误。 */
  private void fail(String message) {
    throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ACCESS", message);
  }
}
