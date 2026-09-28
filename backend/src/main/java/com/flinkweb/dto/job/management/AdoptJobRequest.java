package com.flinkweb.dto.job.management;

/** 承载纳管确认参数；名称、UID 和资源版本共同标识用户确认的现有作业。 */
public record AdoptJobRequest(
    @jakarta.validation.constraints.NotBlank
        @jakarta.validation.constraints.Pattern(regexp = "[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?")
        String name,
    @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 128)
        String uid,
    @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 128)
        String resourceVersion) {}
