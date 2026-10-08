package com.flinkweb.controller.system;

import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.dto.system.SystemInfo;
import com.flinkweb.service.system.SystemService;
import org.springframework.web.bind.annotation.*;

/** 提供平台版本、Namespace 和功能能力查询接口。 */
@RestController
@RequestMapping("/api/system")
public class SystemController {
  private final SystemService service;
  private final com.flinkweb.service.system.DownloaderSettingsService downloader;
  private final com.flinkweb.service.system.OperatorService operator;

  /** 注入系统信息查询服务。 */
  public SystemController(
      SystemService service,
      com.flinkweb.service.system.OperatorService operator,
      com.flinkweb.service.system.DownloaderSettingsService downloader) {
    this.service = service;
    this.downloader = downloader;
    this.operator = operator;
  }

  /** 显式获取 Operator 状态，供系统设置及创建作业的版本筛选使用。 */
  @GetMapping("/operator")
  public ApiResponse<com.fasterxml.jackson.databind.node.ObjectNode> operator() {
    return ApiResponse.ok(operator.inspect());
  }

  /** 查询全平台唯一下载器镜像绑定，供设置页展示。 */
  @GetMapping("/downloader")
  public ApiResponse<com.fasterxml.jackson.databind.node.ObjectNode> downloader() {
    return ApiResponse.ok(downloader.get());
  }

  /** 仅允许管理员修改全平台唯一下载器镜像绑定。 */
  @PutMapping("/downloader")
  @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<com.fasterxml.jackson.databind.node.ObjectNode> saveDownloader(
      @RequestBody com.fasterxml.jackson.databind.node.ObjectNode input) {
    return ApiResponse.ok(downloader.save(input));
  }

  /** 返回平台版本、当前 Namespace、存储类型及功能说明。 */
  @GetMapping
  public ApiResponse<SystemInfo> info() {
    return ApiResponse.ok(service.info());
  }
}
