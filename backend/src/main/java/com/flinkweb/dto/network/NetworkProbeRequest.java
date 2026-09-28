package com.flinkweb.dto.network;

import jakarta.validation.constraints.*;

/** 单目标网络端口诊断参数，拒绝空主机与越界端口。 */
public record NetworkProbeRequest(
    @NotBlank @Size(max = 253) String host,
    @NotNull @Min(1) @Max(65535) @Digits(integer = 5, fraction = 0) java.math.BigDecimal port) {}
