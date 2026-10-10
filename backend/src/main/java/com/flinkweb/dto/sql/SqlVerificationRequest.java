package com.flinkweb.dto.sql;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** 验证已保存的 SQL 修订；UUID 请求编号保护重复提交。 */
public record SqlVerificationRequest(
    @Min(1) long revision,
    @NotBlank
        @Pattern(
            regexp = "[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")
        String requestId) {}
