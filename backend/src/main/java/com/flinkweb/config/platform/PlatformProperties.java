package com.flinkweb.config.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 绑定平台 Namespace、元数据存储和初始管理员配置。 */
@ConfigurationProperties(prefix = "platform")
public record PlatformProperties(String namespace, Storage storage, Security security) {
  /** 描述本地 SQLite 文件或 MySQL 数据源，以及首次创建本地文件的开关。 */
  public record Storage(
      String type,
      String localPath,
      boolean initialize,
      String mysqlUrl,
      String mysqlUsername,
      String mysqlPassword) {}

  /** 描述首次启动时创建管理员所需的账号和密码。 */
  public record Security(String username, String password) {}
}
