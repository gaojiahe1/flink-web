package com.flinkweb.entity.job;

import java.time.Instant;

/** 保存纳管作业与 Kubernetes UID 的绑定，防止同名资源重建后被误操作。 */
public record JobBinding(
    String jobId,
    String namespace,
    String name,
    String resourceUid,
    String resourceVersion,
    Instant importedAt) {}
