package com.flinkweb.datasource.metadata;

import com.flinkweb.config.platform.PlatformProperties;
import com.flinkweb.exception.platform.StartupConfigurationException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;

/** 选择 SQLite 或 MySQL 连接池，管理本地文件锁及数据库初始化。 */
@Configuration
public class StorageConfiguration {
  /** 校验本地文件及目录并持有进程锁；MySQL 模式不占用文件锁。 */
  @Bean(destroyMethod = "close")
  LocalStoreLock localStoreLock(PlatformProperties properties) throws IOException {
    if (!"local".equals(properties.storage().type())) return new LocalStoreLock(null, null);
    Path database = Path.of(properties.storage().localPath()).toAbsolutePath().normalize();
    if (!Files.exists(database) && !properties.storage().initialize()) {
      throw new StartupConfigurationException(
          "本地数据库不存在：" + database + "；FLINK_WEB_STORAGE_INITIALIZE=false，已拒绝自动创建空库。",
          "首次空 PVC 部署：设置 FLINK_WEB_STORAGE_INITIALIZE=true，启动成功后恢复 false。"
              + "已有环境：检查 FLINK_WEB_LOCAL_PATH、PVC/PV 及 /app/data 挂载，恢复原数据库；"
              + "不要开启初始化来掩盖数据卷丢失。S3 只保存制品和作业状态，不替代本地元数据库。");
    }
    Files.createDirectories(database.getParent());
    var channel =
        FileChannel.open(
            database.resolveSibling(database.getFileName() + ".instance.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    FileLock lock;
    try {
      lock = channel.tryLock();
    } catch (RuntimeException | IOException ex) {
      channel.close();
      throw ex;
    }
    if (lock == null) {
      channel.close();
      throw new IllegalStateException("Another instance is using this local database.");
    }
    return new LocalStoreLock(channel, lock);
  }

  /** 根据配置创建 SQLite 或 MySQL 连接池，SQLite 使用单连接和 DELETE 回滚日志模式。 */
  @Bean(destroyMethod = "close")
  HikariDataSource dataSource(PlatformProperties properties, LocalStoreLock ignored) {
    var storage = properties.storage();
    var config = new HikariConfig();
    config.setPoolName("flink-web");
    if ("local".equals(storage.type())) {
      var sqlite = new SQLiteConfig();
      sqlite.enforceForeignKeys(true);
      sqlite.setBusyTimeout(5000);
      sqlite.setJournalMode(SQLiteConfig.JournalMode.DELETE);
      sqlite.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
      config.setDataSourceProperties(sqlite.toProperties());
      config.setJdbcUrl("jdbc:sqlite:" + Path.of(storage.localPath()).toAbsolutePath().normalize());
      config.setDriverClassName("org.sqlite.JDBC");
      config.setMaximumPoolSize(1);
      config.setConnectionTestQuery("SELECT 1");
    } else if ("mysql".equals(storage.type())) {
      config.setJdbcUrl(storage.mysqlUrl());
      config.setUsername(storage.mysqlUsername());
      config.setPassword(storage.mysqlPassword());
      config.setDriverClassName("com.mysql.cj.jdbc.Driver");
      config.setMaximumPoolSize(10);
    } else {
      throw new IllegalStateException("platform.storage.type must be mysql or local");
    }
    return new HikariDataSource(config);
  }

  /** 创建初始化器，确保业务仓库使用前完成数据库迁移。 */
  @Bean
  SchemaInitializer schemaInitializer(DataSource dataSource, PlatformProperties properties) {
    return new SchemaInitializer(new JdbcTemplate(dataSource), properties);
  }

  /** 持有本地数据库文件的进程锁，阻止多个实例同时使用同一 SQLite 文件。 */
  public static final class LocalStoreLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    /** 保存已取得的文件通道与进程锁，用于应用关闭时释放。 */
    LocalStoreLock(FileChannel channel, FileLock lock) {
      this.channel = channel;
      this.lock = lock;
    }

    /** 释放文件锁并关闭锁文件通道。 */
    @Override
    public void close() throws IOException {
      if (lock != null) lock.release();
      if (channel != null) channel.close();
    }
  }
}
