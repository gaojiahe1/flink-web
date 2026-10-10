package com.flinkweb.dto.job.creation;

import jakarta.validation.constraints.*;

/** 复制源草稿为独立作业的名称及源修订号。 */
public record CopyJobRequest(
    @NotBlank @Size(max = 63) @Pattern(regexp = "[a-z0-9]([-a-z0-9]*[a-z0-9])?") String name,
    @PositiveOrZero long revision,
    boolean allowFormRewrite) {
  /** 默认复制后仍使用原编辑限制，只有明确选择才开放表单。 */
  public CopyJobRequest(String name, long revision) {
    this(name, revision, false);
  }
}
