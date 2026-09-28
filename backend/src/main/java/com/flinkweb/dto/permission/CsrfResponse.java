package com.flinkweb.dto.permission;

/** 承载 CSRF 请求头名称及令牌，供前端提交写操作。 */
public record CsrfResponse(String headerName, String token) {}
