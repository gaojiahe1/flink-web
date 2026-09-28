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
    Instant updatedAt) {}
