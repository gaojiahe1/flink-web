package com.flinkweb.dto.job.creation;

import jakarta.validation.constraints.*;

/** 承载新增或修改作业草稿的输入，revision 用于乐观并发控制。 */
public record DraftRequest(
    @NotBlank @Size(max = 63) @Pattern(regexp = "[a-z0-9]([-a-z0-9]*[a-z0-9])?") String name,
    @NotNull @Size(max = 500) String description,
    @NotBlank @Pattern(regexp = "(1\\.(16|17|18|19|20)|2\\.[0-3])(\\.[0-9]+)?") String flinkVersion,
    @NotBlank @Size(max = 200000) String yaml,
    @PositiveOrZero long revision) {}
