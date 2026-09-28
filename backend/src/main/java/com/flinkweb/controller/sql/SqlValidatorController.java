package com.flinkweb.controller.sql;

import com.flinkweb.dto.common.ApiResponse;
import com.flinkweb.service.sql.SqlJobService;
import com.flinkweb.service.sql.SqlParserService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** 已安装 SQL 版本校验器查询接口。 */
@RestController
@RequestMapping("/api")
public class SqlValidatorController {
  private final SqlParserService parser;
  private final SqlJobService sql;

  /** 注入本模块接口依赖，不在控制器内执行持久化逻辑。 */
  public SqlValidatorController(SqlParserService parser, SqlJobService sql) {
    this.parser = parser;
    this.sql = sql;
  }

  /** 列出已安装的版本隔离校验器。 */
  @GetMapping("/platform/sql-validators")
  public ApiResponse<List<String>> parsers() {
    return ApiResponse.ok(parser.installed());
  }
}
