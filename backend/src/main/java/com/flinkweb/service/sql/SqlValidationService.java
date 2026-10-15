package com.flinkweb.service.sql;

import com.flinkweb.dto.sql.SqlCheckResult;
import java.util.*;

/** 服务契约：检查 SQL 脚本边界、引号、语句顺序及基础支持范围；不连接外部 Catalog 或运行 Flink Planner。 */
public interface SqlValidationService {
  /** 拆分脚本并限制为先建表/视图、后 INSERT 的单 Application 执行序列。 */
  SqlCheckResult validate(String script);
  /** 验证入口另外允许只读 SELECT/CTE；Application 发布仍要求 INSERT。 */
  SqlCheckResult validateVerification(String script);
}
