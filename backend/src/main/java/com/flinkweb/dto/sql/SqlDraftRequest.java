package com.flinkweb.dto.sql;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.*;

/** SQL 草稿输入；运行参数沿用 JAR 表单，保存草稿不会访问集群。 */
public record SqlDraftRequest(
    @NotBlank @Size(max = 63) @Pattern(regexp = "[a-z0-9]([-a-z0-9]*[a-z0-9])?") String name,
    @NotNull @Size(max = 500) String description,
    @NotNull @Size(max = 32768) String sql,
    @NotNull ObjectNode runtime,
    @PositiveOrZero long revision) {}
