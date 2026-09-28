package com.flinkweb.dto.system;

/** 承载平台版本、Namespace、存储类型和功能列表。 */
public record SystemInfo(
    String version,
    String namespace,
    String storageType,
    java.util.List<Capability> capabilities) {}
