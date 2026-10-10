package com.flinkweb.dto.session;

import jakarta.validation.constraints.Min;

/** 启动已保存的 Session 配置，强制提交当前修订号。 */
public record SessionStartRequest(@Min(1) long revision) {}
