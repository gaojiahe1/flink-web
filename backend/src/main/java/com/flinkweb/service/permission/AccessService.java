package com.flinkweb.service.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** 服务契约：管理作业负责人、业务归属及用户组范围权限；平台角色仍由接口层限制。 */
public interface AccessService {
  /** 获取当前身份，供版本与归属记录使用。 */
  String actor();

  /** 判断当前账号是否拥有管理员角色。 */
  boolean admin();

  /** 单工作空间全部作业共享，按平台角色核验读取/编辑/运行操作。 */
  boolean allowed(String resource, String action);

  /** 每个请求取得角色快照，用于批量列表过滤，不跨请求缓存。 */
  java.util.function.BiPredicate<String, String> evaluator();

  /** 对直接 API 调用执行与列表一致的范围校验。 */
  void require(String resource, String action);

  /** 新建作业时登记负责人；同事务调用避免产生无归属的半成品。 */
  void initialize(String resource);

  /** SQL 首次生成普通作业时复制访问范围，保持两个入口一致。 */
  void copy(String from, String to);

  /** 返回授权规则和当前用户允许的操作，用于页面禁用入口。 */
  ObjectNode get(String resource);

  /** 仅管理员或当前负责人可调整归属；校验成员组和外部监控链接。 */
  ObjectNode save(String resource, ObjectNode input);

  /** 查询用户组；非管理员仅可查看所属组。 */
  List<ObjectNode> groups();

  /** 管理员维护用户组，成员必须为现有账号。 */
  ObjectNode saveGroup(String id, ObjectNode input);
}
