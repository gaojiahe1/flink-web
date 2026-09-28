package com.flinkweb.service.sql;

import com.flinkweb.dto.sql.SqlCheckResult;
import java.nio.file.*;
import java.util.concurrent.*;

/** 服务契约：按镜像的精确 Flink 版本启动隔离 Parser 进程；未安装适配器时明确降级为基础检查。 */
public interface SqlParserService {
  /** 查询已安装的适配器，返回真实文件存在情况。 */
  java.util.List<String> installed();

  /** 在十秒和 256 MiB 限制内执行 Parser，结构检查与版本语法检查分别呈现。 */
  SqlCheckResult check(SqlCheckResult basic, String imageId);
}
