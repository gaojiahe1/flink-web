package com.flinkweb;

import com.flinkweb.config.platform.PlatformProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Spring Boot 启动入口，启用平台配置属性绑定。
 */
@SpringBootApplication
@EnableConfigurationProperties(PlatformProperties.class)
public class FlinkWebApplication {
    /**
     * 启动 Spring Boot 应用及其配置、接口和数据源。
     */
    public static void main(String[] args) {
        SpringApplication.run(FlinkWebApplication.class, args);
    }
}
