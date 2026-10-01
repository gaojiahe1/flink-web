package com.flinkweb.entity.job;

import java.time.Instant;

/** 保存作业草稿、Flink 版本及修订号；不保存实时运行状态。 */
public record JobDraft(
    String id,
    String name,
    String description,
    String flinkVersion,
    String yaml,
    long revision,
    Instant createdAt,
    Instant updatedAt,
    long jobNumber,
    String deploymentName) {
  /** 生成至少七位的十进制资源编号；超过七位时不截断。 */
  public static String numberedName(long number) {
    return "flink-" + String.format(java.util.Locale.ROOT, "%07d", number);
  }

  /** 兼容历史草稿及纳管入口，新作业由数据库分配编号。 */
  public JobDraft(
      String id,
      String name,
      String description,
      String flinkVersion,
      String yaml,
      long revision,
      Instant createdAt,
      Instant updatedAt) {
    this(id, name, description, flinkVersion, yaml, revision, createdAt, updatedAt, 0, name);
  }
}
