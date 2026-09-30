package com.flinkweb.service.job.management.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.collector.job.FlinkStateCollector;
import com.flinkweb.config.platform.PlatformProperties;
import com.flinkweb.repository.job.JobBindingRepository;
import com.flinkweb.repository.job.JobRepository;
import com.flinkweb.service.job.management.JobQueryService;
import com.flinkweb.service.job.runtime.SnapshotService;
import com.flinkweb.service.permission.AccessService;
import org.springframework.stereotype.Service;

/** 合并本次集群快照与平台登记信息，不缓存运行状态，仅登记本次观测到的快照及操作结果。 */
@Service
public class JobQueryServiceImpl implements JobQueryService {
  private final FlinkStateCollector collector;
  private final JobRepository jobs;
  private final JobBindingRepository bindings;
  private final String namespace;
  private final AccessService access;
  private final SnapshotService snapshots;
  private final com.flinkweb.service.job.runtime.StopService stops;

  /** 注入按需采集器、草稿仓库和资源绑定仓库。 */
  public JobQueryServiceImpl(
      FlinkStateCollector collector,
      JobRepository jobs,
      JobBindingRepository bindings,
      PlatformProperties properties,
      AccessService access,
      SnapshotService snapshots,
      com.flinkweb.service.job.runtime.StopService stops) {
    this.collector = collector;
    this.jobs = jobs;
    this.bindings = bindings;
    this.namespace = properties.namespace();
    this.access = access;
    this.snapshots = snapshots;
    this.stops = stops;
  }

  /** 即时采集集群状态，标记已纳管、可纳管及同名身份冲突的作业。 */
  public ObjectNode refresh() {
    ObjectNode result = collector.collect();
    var registered = jobs.findAll();
    var bound = bindings.findAll();
    var stopProgress = result.putObject("stopProgress");
    for (var job : registered) {
      if (!access.evaluator().test(job.id(), "operate")) continue;
      try {
        var progress = stops.advance(job.id());
        if (progress != null) {
          var visibleProgress = stopProgress.putObject(job.id());
          for (String field : java.util.List.of("phase", "message", "savepointPath"))
            if (progress.has(field)) visibleProgress.set(field, progress.get(field));
        }
      } catch (com.flinkweb.exception.common.BusinessException ex) {
        stopProgress.putObject(job.id()).put("phase", "ERROR").put("message", ex.getMessage());
      }
    }
    var permitted = access.evaluator();
    for (JsonNode item : result.path("items")) {
      ObjectNode row = (ObjectNode) item;
      var local =
          registered.stream().filter(j -> j.deploymentName().equals(row.path("name").asText())).findFirst();
      boolean nameConflict = registered.stream().anyMatch(j -> j.name().equals(row.path("name").asText()));
      boolean managed = false;
      if (local.isPresent()) {
        var binding = bound.stream().filter(b -> b.jobId().equals(local.get().id())).findFirst();
        managed =
            binding.isPresent()
                ? namespace.equals(binding.get().namespace())
                    && binding.get().resourceUid().equals(row.path("uid").asText())
                : local.get().id().equals(row.path("ownerJobId").asText());
      }
      row.put("managed", managed)
          .put(
              "canAdopt",
              !managed
                  && local.isEmpty()
                  && !nameConflict
                  && row.path("application").asBoolean()
                  && !row.path("deleting").asBoolean());
      row.put("registrationConflict", !managed && (local.isPresent() || nameConflict));
      if (managed) {
        row.put("jobId", local.orElseThrow().id());
        if (permitted.test(local.orElseThrow().id(), "read"))
          snapshots.observe(row, result.path("queriedAt").asText());
      }
    }
    var filtered = result.arrayNode();
    for (var item : result.path("items")) {
      var local =
          registered.stream().filter(j -> j.deploymentName().equals(item.path("name").asText())).findFirst();
      if (local.isPresent() ? permitted.test(local.get().id(), "read") : access.admin())
        filtered.add(item);
    }
    result.set("items", filtered);
    return result;
  }
}
