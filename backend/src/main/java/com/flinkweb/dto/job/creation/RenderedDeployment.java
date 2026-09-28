package com.flinkweb.dto.job.creation;

/** 承载变量替换后的部署资源以及待写入 Kubernetes 的 Secret。 */
public record RenderedDeployment(
    com.fasterxml.jackson.databind.node.ObjectNode deployment,
    java.util.List<com.fasterxml.jackson.databind.node.ObjectNode> secrets) {}
