package com.flinkweb.dto.permission;

/** 承载登录用户名及角色集合，不暴露密码和凭据。 */
public record CurrentUser(String username, java.util.List<String> roles) {}
