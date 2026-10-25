package com.flinkweb.controller.sql.catalog;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.service.sql.catalog.FlinkCatalogService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Flink Catalog 登记、初始化和显式元数据查询接口；写操作仅管理员可用。 */
@RestController
@RequestMapping("/api/sql/catalogs")
public class FlinkCatalogController {
  private final FlinkCatalogService service;

  /** 注入目录服务接口。 */
  public FlinkCatalogController(FlinkCatalogService service) {
    this.service = service;
  }

  /** 管理员手动测试，非 JDBC 目录只执行配置校验。 */
  @PostMapping("/{id}/test")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<ObjectNode> test(@PathVariable String id) {
    return ApiResponse.ok(service.test(id));
  }

  /** 返回可选目录，不执行远程探测。 */
  @GetMapping
  public ApiResponse<List<ObjectNode>> list() {
    return ApiResponse.ok(service.list());
  }

  /** 开发人员主动读取目录函数列表或详情，不执行函数。 */
  @PostMapping("/{id}/functions")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<ObjectNode> functions(@PathVariable String id, @RequestBody ObjectNode input) {
    return ApiResponse.ok(
        service.functions(id, input.path("database").asText(), input.path("name").asText()));
  }

  /** 查看有权读取的引用草稿，删除保护不因列表过滤而放宽。 */
  @GetMapping("/{id}/usage")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<ObjectNode> usage(@PathVariable String id) {
    return ApiResponse.ok(service.usage(id));
  }

  /** 创建或更新目录，使用请求中的 revision 控制并发。 */
  @PutMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<ObjectNode> save(@PathVariable String id, @RequestBody ObjectNode input) {
    return ApiResponse.ok(service.save(id, input));
  }

  /** 删除未引用目录，不删除任何远端表。 */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ApiResponse<Void> delete(@PathVariable String id, @RequestParam long revision) {
    service.delete(id, revision);
    return ApiResponse.ok(null);
  }

  /** 预览目录初始化代码和密钥引用，不返回密码。 */
  @GetMapping("/{id}/snippet")
  public ApiResponse<ObjectNode> snippet(
      @PathVariable String id, @RequestParam(defaultValue = "") String database) {
    return ApiResponse.ok(service.snippet(id, database));
  }

  /** 开发人员或管理员手动浏览元数据，不执行用户 SQL。 */
  @PostMapping("/{id}/metadata")
  @PreAuthorize("hasAnyRole('ADMIN','DEVELOPER')")
  public ApiResponse<ObjectNode> metadata(@PathVariable String id, @RequestBody ObjectNode input) {
    return ApiResponse.ok(
        service.metadata(id, input.path("database").asText(), input.path("table").asText()));
  }
}
