package com.flinkweb.controller.job.runtime;

import com.flinkweb.service.job.runtime.FlinkUiService;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.web.bind.annotation.*;

/** 提供同源 Flink UI 路由入口，将校验及代理转发交给业务服务。 */
@RestController
public class FlinkUiController {
  private final FlinkUiService service;

  /** 注入 Flink UI 代理业务服务。 */
  public FlinkUiController(FlinkUiService service) {
    this.service = service;
  }

  /** 将不带尾斜杠的 UI 地址规范化，保证相对静态资源路径正确。 */
  @GetMapping("/flink-ui/{id}")
  public void trailingSlash(@PathVariable String id, HttpServletResponse response)
      throws IOException {
    service.trailingSlash(id, response);
  }

  /** 将同源 UI 请求交给代理服务处理。 */
  @GetMapping("/flink-ui/{id}/**")
  public void proxy(
      @PathVariable String id, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    service.proxy(id, request, response);
  }
}
