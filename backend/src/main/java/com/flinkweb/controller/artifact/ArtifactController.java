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

  /** 注入制品业务服务，供上传、目录及下载接口使用。 */
  public ArtifactController(ArtifactService s3) {
    this.s3 = s3;
  }

  /** 接收 S3 目录和分页参数，返回制品目录。 */
  @GetMapping("/objects")
  public ApiResponse<ObjectNode> list(
      @RequestParam(required = false) String prefix, @RequestParam(required = false) String token) {
    return ApiResponse.ok(s3.list(prefix, token));
  }

  /** 接收上传文件，由业务层写入 S3 并登记元数据。 */
  @PostMapping("/upload")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<ObjectNode> upload(
      @RequestParam("file") MultipartFile file,
      @RequestParam(defaultValue = "shared") String category) {
    return ApiResponse.ok(s3.upload(file, category));
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
