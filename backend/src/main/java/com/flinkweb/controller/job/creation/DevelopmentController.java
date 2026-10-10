package com.flinkweb.controller.job.creation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.service.job.creation.DevelopmentService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** JAR 开发列表及作业编辑映射接口。 */
@RestController
@RequestMapping("/api/development")
public class DevelopmentController {
  private final DevelopmentService development;

  /** 注入开发映射服务。 */
  public DevelopmentController(DevelopmentService development) {
    this.development = development;
  }

  /** 读取开发入口映射。 */
  @GetMapping("/links")
  public ApiResponse<ObjectNode> links() {
    return ApiResponse.ok(development.links());
  }

  /** 读取独立 JAR 草稿列表。 */
  @GetMapping("/jar/jobs")
  public ApiResponse<List<JobDraft>> jars() {
    return ApiResponse.ok(development.jarJobs());
  }

  /** 独立查询部署标记，失败不影响开发草稿列表。 */
  @GetMapping("/jar/deployment-status")
  public ApiResponse<ObjectNode> deploymentStatus() {
    return ApiResponse.ok(development.deploymentStatus());
  }

  /** 首次编辑旧作业时加入开发区。 */
  @PostMapping("/jar/jobs/{id}/open")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<JobDraft> open(@PathVariable String id) {
    return ApiResponse.ok(development.openJar(id));
  }
}
