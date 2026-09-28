package com.flinkweb.controller.catalog;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.service.catalog.CatalogService;
import com.flinkweb.service.permission.SecretTransportService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 提供模板、镜像、配置项和制品登记的 HTTP 接口。 */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {
  private final CatalogService service;
  private final SecretTransportService transport;

  /** 注入目录维护业务服务。 */
  public CatalogController(CatalogService service, SecretTransportService transport) {
    this.service = service;
    this.transport = transport;
  }

  /** 列出指定目录的数据，敏感配置由业务层脱敏。 */
  @GetMapping("/{type}")
  public ApiResponse<List<ObjectNode>> list(@PathVariable String type) {
    return ApiResponse.ok(service.list(type));
  }

  /** 新增或按修订号更新模板、镜像、配置或制品登记。 */
  @PutMapping("/{type}/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<ObjectNode> save(
      @PathVariable String type,
      @PathVariable String id,
      @RequestBody ObjectNode input,
      HttpServletRequest request) {
    var decoded = input.deepCopy();
    if (type.equals("configs")
        && input.path("kind").asText().equals("secret")
        && !input.path("value").asText("").isEmpty())
      decoded.put(
          "value",
          transport.decrypt(
              request.getSession(false), "config-secret", id, input.path("value").asText()));
    return ApiResponse.ok(service.save(type, id, decoded));
  }

  /** 根据目录、记录标识和修订号删除元数据。 */
  @DeleteMapping("/{type}/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<Void> delete(
      @PathVariable String type, @PathVariable String id, @RequestParam long revision) {
    service.delete(type, id, revision);
    return ApiResponse.ok(null);
  }
}
