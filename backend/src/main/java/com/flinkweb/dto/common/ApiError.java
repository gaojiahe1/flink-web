package com.flinkweb.dto.common;

/** 承载可供前端识别的错误码和可展示的错误说明。 */
public record ApiError(String code, String message) {}
