package com.flinkweb.service.system;

import com.flinkweb.dto.system.SystemInfo;

/** 服务契约：汇总平台配置和功能说明，供前端显示连接范围与能力边界。 */
public interface SystemService {
  /** 汇总版本、Namespace、存储类型和支持的功能说明。 */
  SystemInfo info();
}
