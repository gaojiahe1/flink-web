package com.flinkweb.service.job.release.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.job.creation.DraftRequest;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.job.release.ReleaseService;
import com.flinkweb.service.permission.AccessService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 保存不可变的发布内容与校验摘要；回滚只恢复草稿，部署和状态恢复由用户单独执行。 */
@Service
public class ReleaseServiceImpl implements ReleaseService {
  private final RecordRepository records;
  private final ObjectMapper mapper;
  private final AccessService access;

  /** 注入发布存储、JSON 序列化及范围权限。 */
  public ReleaseServiceImpl(RecordRepository records, ObjectMapper mapper, AccessService access) {
    this.records = records;
    this.mapper = mapper;
    this.access = access;
  }

  /** 固定本次提交内容，不保存生成 Secret 的明文或密文数据。 */
  public ObjectNode capture(JobDraft job, ObjectNode rendered) {
    access.require(job.id(), "edit");
    ObjectNode entry =
        mapper
            .createObjectNode()
            .put("jobId", job.id())
            .put("name", job.name())
            .put("draftRevision", job.revision())
            .put("flinkVersion", job.flinkVersion())
            .put("description", job.description())
            .put("sourceYaml", job.yaml())
            .put("actor", access.actor());
    ObjectNode manifest = rendered.deepCopy();
    manifest.remove("status");
    if (manifest.path("metadata").isObject())
      ((ObjectNode) manifest.get("metadata"))
          .remove(List.of("uid", "resourceVersion", "managedFields", "creationTimestamp"));
    entry.set("manifest", manifest);
    entry.put("image", rendered.path("spec").path("image").asText());
    var refs = entry.putArray("configReferences");
    var matcher =
        java.util.regex.Pattern.compile("\\{\\{(?:config|secret):([a-z0-9_.-]+)}}")
            .matcher(job.yaml());
    Set<String> seen = new HashSet<>();
    while (matcher.find())
      if (seen.add(matcher.group(1))) {
        var config = records.require("configs", matcher.group(1));
        refs.addObject()
            .put("id", matcher.group(1))
            .put("revision", config.path("revision").asLong())
            .put("kind", config.path("kind").asText());
      }
    var artifacts = entry.putArray("artifacts");
    collectArtifacts(rendered, artifacts, new HashSet<>());
    for (var sql : records.list("sql_jobs"))
      if (job.id().equals(sql.path("jobId").asText())) {
        ObjectNode snapshot = sql.deepCopy();
        snapshot.remove(List.of("createdAt", "updatedAt"));
        entry.set("sqlDraft", snapshot);
        break;
      }
    entry.put("contentSha256", sha(entry.toString()));
    return records.save("releases", UUID.randomUUID().toString(), entry, 0);
  }

  /** 从实际下载器清单中提取精确制品地址和 SHA，不以最新目录记录冒充历史。 */
  private void collectArtifacts(
      JsonNode node, com.fasterxml.jackson.databind.node.ArrayNode target, Set<String> seen) {
    if (node.isObject() && "ARTIFACT_MANIFEST".equals(node.path("name").asText())) {
      try {
        for (JsonNode file : mapper.readTree(node.path("value").asText())) {
          String id = file.path("uri").asText() + file.path("sha256").asText();
          if (seen.add(id))
            target
                .addObject()
                .put("uri", file.path("uri").asText())
                .put("sha256", file.path("sha256").asText())
                .put("filename", file.path("filename").asText());
        }
      } catch (Exception ex) {
        throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_MANIFEST", "制品清单无法解析");
      }
    }
    if (node.isContainerNode()) for (JsonNode child : node) collectArtifacts(child, target, seen);
  }

  /** 返回指定作业的不可变版本，新版本优先。 */
  public List<ObjectNode> list(String id) {
    access.require(id, "read");
    return records.listForSubject("releases", id).stream()
        .sorted(Comparator.comparing((ObjectNode n) -> n.path("createdAt").asText()).reversed())
        .toList();
  }

  /** 验证发布版本确实属于当前作业，防止跨作业恢复。 */
  public ObjectNode require(String jobId, String releaseId) {
    access.require(jobId, "read");
    var release = records.require("releases", releaseId);
    if (!jobId.equals(release.path("jobId").asText()))
      throw new BusinessException(HttpStatus.NOT_FOUND, "RELEASE_NOT_FOUND", "该作业没有此发布版本");
    return release;
  }

  /** 恢复发布时的声明式 YAML；密钥引用重新解析，不宣称密钥内容已回滚。 */
  public JobDraft restore(String id, String releaseId, long revision, JobService jobs) {
    access.require(id, "edit");
    var release = require(id, releaseId);
    return jobs.update(
        id,
        new DraftRequest(
            release.path("name").asText(),
            release.path("description").asText(),
            release.path("flinkVersion").asText(),
            release.path("sourceYaml").asText(),
            revision));
  }

  /** 生成可复核的内容摘要。 */
  private String sha(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }
}
