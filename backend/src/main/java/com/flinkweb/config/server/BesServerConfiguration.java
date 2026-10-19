package com.flinkweb.config.server;

import com.bes.enterprise.springboot.embedded.BesServletWebServerFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 将 BES 运行文件放在显式可写目录，兼容只读镜像；不修改厂商 bundle。 */
@Configuration(proxyBeanMethods = false)
public class BesServerConfiguration {
  /** 当前 starter 未将 basedir 属性传给工厂，因此在初始化之前显式设置目录。 */
  @Bean
  WebServerFactoryCustomizer<BesServletWebServerFactory> besRuntimeDirectory(
      @Value("${server.bes.basedir}") String directory) {
    return factory -> {
      Path base = Path.of(directory).toAbsolutePath().normalize();
      try {
        Files.createDirectories(base.resolve("license"));
      } catch (IOException error) {
        throw new UncheckedIOException("BES 运行目录不可写", error);
      }
      // BES 的初始化优先读取进程级 installRoot；后续上下文不能沿用旧目录。
      System.setProperty("com.bes.installRoot", base.toString());
      factory.setBaseDirectory(base.toFile());
    };
  }
}
