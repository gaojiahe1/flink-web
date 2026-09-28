package com.flinkweb.service.audit.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.audit.AuditService;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** 记录操作人、动作和结果，并提供审计记录查询。 */
@Service
public class AuditServiceImpl implements AuditService {
  private final RecordRepository store;
  private final ObjectMapper mapper;

  /** 注入元数据仓库与 JSON 映射器，供审计记录读写使用。 */
  public AuditServiceImpl(RecordRepository store, ObjectMapper mapper) {
    this.store = store;
    this.mapper = mapper;
  }

  /** 查询已持久化的审计记录，时间排序由展示层处理。 */
  public java.util.List<com.fasterxml.jackson.databind.node.ObjectNode> list() {
    return store.list("audit");
  }

  /** 保存当前操作人、动作、目标及执行结果，不记录凭据内容。 */
  public void record(String action, String target, String outcome) {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    var value =
        mapper
            .createObjectNode()
            .put("actor", auth == null ? "system" : auth.getName())
            .put("action", action)
            .put("target", target)
            .put("outcome", outcome)
            .put("time", Instant.now().toString());
    store.save("audit", UUID.randomUUID().toString(), value, 0);
  }
}
