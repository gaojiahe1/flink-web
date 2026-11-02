package com.flinkweb.service.system.impl;

import com.flinkweb.config.platform.PlatformProperties;
import com.flinkweb.dto.system.Capability;
import com.flinkweb.dto.system.SystemInfo;
import com.flinkweb.service.system.SystemService;
import java.util.List;
import org.springframework.stereotype.Service;

/** 汇总平台配置和功能说明，供前端显示连接范围与能力边界。 */
@Service
public class SystemServiceImpl implements SystemService {
  private final PlatformProperties properties;

  /** 读取 Namespace 和持久化配置，供系统信息接口使用。 */
  public SystemServiceImpl(PlatformProperties properties) {
    this.properties = properties;
  }

  /** 汇总版本、Namespace、存储类型和支持的功能说明。 */
  public SystemInfo info() {
    return new SystemInfo(
        "0.0.10",
        properties.namespace(),
        properties.storage().type(),
        List.of(
                "drafts",
                "sql-drafts",
                "kubernetes",
                "adoption",
                "templates",
                "images",
                "configs",
                "artifacts",
                "users",
                "flink-ui-proxy")
            .stream()
            .map(name -> new Capability(name, true))
            .toList());
  }
}
