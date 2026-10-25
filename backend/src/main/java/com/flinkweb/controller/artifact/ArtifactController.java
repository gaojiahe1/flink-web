package com.flinkweb.controller.artifact;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.service.artifact.ArtifactService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** 提供制品目录、上传和下载 HTTP 接口，将存储操作交给业务服务。 */
@RestController
@RequestMapping("/api/artifacts")
public class ArtifactController {
  private final ArtifactService s3;
  private final com.flinkweb.service.artifact.support.JarEntryInspector inspector;

  /** 注入制品业务服务，供上传、目录及下载接口使用。 */
  public ArtifactController(
      ArtifactService s3, com.flinkweb.service.artifact.support.JarEntryInspector inspector) {
    this.s3 = s3;
    this.inspector = inspector;
  }

  /** 静态分析用户选中的主 JAR 入口类，不执行用户代码。 */
  @PostMapping("/entry-classes")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<ObjectNode> entryClasses(@RequestBody ObjectNode input) {
    return ApiResponse.ok(inspector.inspect(input.path("reference").asText()));
  }

  /** 识别一个 JAR 内全部可实例化的 Flink UDF，不加载用户代码。 */
  @PostMapping("/udf-functions")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<ObjectNode> udfFunctions(@RequestBody ObjectNode input) {
    return ApiResponse.ok(inspector.inspect(input.path("reference").asText()));
  }

  /** 接收 S3 目录和分页参数，返回制品目录。 */
  @GetMapping("/objects")
  public ApiResponse<ObjectNode> list(
      @RequestParam(required = false) String prefix, @RequestParam(required = false) String token) {
    return ApiResponse.ok(s3.list(prefix, token));
  }

  /** 接收上传文件，由业务层直接写入所选 S3 目录。 */
  @PostMapping("/upload")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<ObjectNode> upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam(defaultValue = "shared") String category,
      @RequestParam(required = false) String prefix) {
    return ApiResponse.ok(s3.upload(file, prefix == null ? category : prefix));
  }

  /** 返回不含凭证的 S3 默认连接配置。 */
  @GetMapping("/defaults")
  public ApiResponse<ObjectNode> defaults() {
    return ApiResponse.ok(s3.defaults());
  }

  /** 创建允许范围内的对象目录。 */
  @PostMapping("/directories")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<ObjectNode> mkdir(@RequestBody ObjectNode input) {
    return ApiResponse.ok(s3.mkdir(input.path("prefix").asText()));
  }

  /** 删除明确选中的目录及当前对象，禁止删除桶根目录。 */
  @DeleteMapping("/directories")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<ObjectNode> deleteDirectory(@RequestParam String prefix) {
    return ApiResponse.ok(s3.deleteDirectory(prefix));
  }

  /** 删除明确指定的单个对象。 */
  @DeleteMapping("/objects")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<ObjectNode> delete(@RequestParam String key) {
    return ApiResponse.ok(s3.delete(key));
  }

  /** 校验登录权限后转发制品下载流，完成后释放资源。 */
  @GetMapping("/download")
  public void download(@RequestParam String key, HttpServletResponse response) throws IOException {
    try (var stream = s3.download(key)) {
      response.setContentType("application/octet-stream");
      response.setHeader("Content-Disposition", "attachment");
      response.setHeader("Cache-Control", "no-store");
      stream.transferTo(response.getOutputStream());
    }
  }
}
