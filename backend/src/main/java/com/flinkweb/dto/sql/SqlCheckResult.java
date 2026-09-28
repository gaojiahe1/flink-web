package com.flinkweb.dto.sql;

import java.util.List;

/** 返回基础脚本检查结果，明确不等同于 Flink Planner 的语义校验。 */
public record SqlCheckResult(
    boolean valid,
    String level,
    int statementCount,
    int insertCount,
    String message,
    List<String> statements) {}
