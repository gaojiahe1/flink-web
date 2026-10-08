package com.flinkweb.dto.job.creation;

import jakarta.validation.constraints.*;

/** 承载新增或修改作业草稿的输入，revision 用于乐观并发控制。 */
public record DraftRequest(
    @NotBlank @Size(max = 63) @Pattern(regexp = "[a-z0-9]([-a-z0-9]*[a-z0-9])?") String name,
    @NotNull @Size(max = 500) String description,
    @NotBlank @Pattern(regexp = "(1\\.(16|17|18|19|20)|2\\.[0-3])(\\.[0-9]+)?") String flinkVersion,
    @NotBlank @Size(max = 200000) String yaml,
    @PositiveOrZero long revision,
    @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        String workspaceId,
    boolean allowFormRewrite) {
  /** 兼容已有内部调用，未指定目录时由服务分配标识。 */
  public DraftRequest(
      String name, String description, String flinkVersion, String yaml, long revision) {
    this(name, description, flinkVersion, yaml, revision, null, false);
  }

  /** 保留指定上传目录的内部调用，默认不授权旧作业转表单。 */
  public DraftRequest(
      String name,
      String description,
      String flinkVersion,
      String yaml,
      long revision,
      String workspaceId) {
    this(name, description, flinkVersion, yaml, revision, workspaceId, false);
  }
}
