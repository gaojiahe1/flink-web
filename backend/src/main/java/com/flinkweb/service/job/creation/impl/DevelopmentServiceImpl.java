package com.flinkweb.service.job.creation.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.job.creation.*;
import com.flinkweb.service.permission.AccessService;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 保存 JAR 开发登记，并从有效 SQL 关联解析正确编辑入口。 */
@Service
public class DevelopmentServiceImpl implements DevelopmentService {
  private final JobService jobs;
  private final RecordRepository records;
  private final AccessService access;
  private final ObjectMapper mapper;
  private final YamlService yaml;
  private final com.flinkweb.client.kubernetes.KubernetesClient client;
  private final com.flinkweb.repository.job.JobBindingRepository bindings;

  /** 注入作业与开发登记仓库，沿用现有访问权限。 */
  public DevelopmentServiceImpl(
      JobService jobs,
      RecordRepository records,
      AccessService access,
      ObjectMapper mapper,
      YamlService yaml,
      com.flinkweb.client.kubernetes.KubernetesClient client,
      com.flinkweb.repository.job.JobBindingRepository bindings) {
    this.yaml = yaml;
    this.jobs = jobs;
    this.records = records;
    this.access = access;
    this.mapper = mapper;
    this.client = client;
    this.bindings = bindings;
  }

  /** 单次查询资源集合，身份匹配后才标记已部署；权限不足由调用方显示未知状态。 */
  public ObjectNode deploymentStatus() {
    ObjectNode result = mapper.createObjectNode();
    var drafts = jarJobs();
    if (drafts.isEmpty()) return result;
    var resources = client.call("GET", client.base(), null).path("items");
    if (!resources.isArray())
      throw new com.flinkweb.exception.common.BusinessException(
          org.springframework.http.HttpStatus.BAD_GATEWAY,
          "DEPLOYMENT_QUERY_FAILED",
          "无法读取 FlinkDeployment 列表，请检查 CRD 和查询权限");
    var bound = bindings.findAll();
    for (var job : drafts) {
      String state = "UNDEPLOYED";
      for (var resource : resources)
        if (job.deploymentName().equals(resource.at("/metadata/name").asText())) {
          var binding = bound.stream().filter(b -> b.jobId().equals(job.id())).findFirst();
          boolean managed =
              binding.isPresent()
                  ? binding.get().resourceUid().equals(resource.at("/metadata/uid").asText())
                  : job.id().equals(resource.at("/metadata/labels/flink-web~1job-id").asText());
          state = managed ? "DEPLOYED" : "CONFLICT";
          break;
        }
      result.put(job.id(), state);
    }
    return result;
  }

  /** 历史作业在用户编辑前不会自动进入开发列表。 */
  public List<JobDraft> jarJobs() {
    Set<String> ids = new HashSet<>();
    records.list("jar_development").forEach(r -> ids.add(r.path("id").asText()));
    return jobs.list().stream().filter(j -> ids.contains(j.id())).toList();
  }

  /** 仅返回当前用户可读取的关联，避免暴露其他用户的 SQL 草稿。 */
  public ObjectNode links() {
    var result = mapper.createObjectNode();
    var allowed = access.evaluator();
    var sql = records.list("sql_jobs");
    for (var job : jobs.list()) {
      var link = result.putObject(job.id()).put("type", "jar");
      for (var record : sql)
        if (job.id().equals(record.path("jobId").asText())) {
          link.put("type", "sql");
          if (allowed.test("sql:" + record.path("id").asText(), "read"))
            link.put("sqlId", record.path("id").asText());
          break;
        }
      var resource = yaml.parse(job.yaml());
      if ("sql".equals(resource.at("/metadata/labels/flink-web~1job-type").asText())
          || "com.flinkweb.sql.SqlRunner".equals(resource.at("/spec/job/entryClass").asText()))
        link.put("type", "sql");
    }
    return result;
  }

  /** 首次编辑仅登记映射，不转换或修改旧作业 YAML。 */
  @Transactional
  public JobDraft openJar(String id) {
    access.require(id, "edit");
    var job = jobs.get(id);
    if ("sql".equals(links().path(id).path("type").asText()))
      throw new com.flinkweb.exception.common.BusinessException(
          org.springframework.http.HttpStatus.CONFLICT,
          "SQL_EDITOR_REQUIRED",
          "SQL 作业请从对应 SQL 草稿编辑");
    if (records.find("jar_development", id) == null)
      records.save("jar_development", id, mapper.createObjectNode(), 0);
    return job;
  }
}
