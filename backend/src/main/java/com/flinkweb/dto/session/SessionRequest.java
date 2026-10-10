package com.flinkweb.dto.session;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.constraints.*;

/** Session 配置输入，运行状态由集群查询获得。 */
public record SessionRequest(
    @NotBlank @Size(max = 45) @Pattern(regexp = "[a-z0-9]([-a-z0-9]*[a-z0-9])?") String name,
    @NotNull @Size(max = 500) String description,
    boolean validation,
    @NotNull ObjectNode runtime,
    @PositiveOrZero long revision) {}
