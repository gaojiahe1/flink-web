package com.flinkweb.dto.system;

/** 描述平台功能及其可用性与使用限制。 */
public record Capability(String name, boolean implemented) {}
