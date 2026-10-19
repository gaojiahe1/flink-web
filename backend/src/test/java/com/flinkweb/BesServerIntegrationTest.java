package com.flinkweb;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.el.ExpressionFactory;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.ClassUtils;

/** 使用真实监听端口验证 BES 实际接管服务器，且 Jakarta EL 不依赖 Tomcat。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class BesServerIntegrationTest {
  private static final Path DIRECTORY;
  private static final int PORT;

  static {
    try {
      DIRECTORY = Files.createTempDirectory("flink-bes-server-");
      try (var socket = new ServerSocket(0)) {
        PORT = socket.getLocalPort();
      }
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  @Autowired ServletWebServerApplicationContext context;
  @Autowired TestRestTemplate http;

  /** 真实服务器的运行目录和 SQLite 使用本轮独占临时目录。 */
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("platform.storage.local-path", () -> DIRECTORY.resolve("test.db").toString());
    registry.add("platform.security.password", () -> "BES-test-password-12345");
    registry.add("platform.security.master-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    registry.add("server.bes.basedir", () -> DIRECTORY.resolve("server").toString());
    registry.add("server.port", () -> PORT);
  }

  /** 健康接口和鉴权经真实 BES 处理，不能由 MockMvc 或 Tomcat 回退冒充。 */
  @Test
  void besServesHealthAndEnforcesAuthentication() {
    assertThat(context.getWebServer().getClass().getName())
        .isEqualTo("com.bes.enterprise.springboot.embedded.BesWebServer");
    assertThat(context.getWebServer().getPort()).isEqualTo(PORT);
    assertThat(Path.of(System.getProperty("com.bes.installRoot")))
        .isEqualTo(DIRECTORY.resolve("server"));
    assertThat(ClassUtils.isPresent("org.apache.catalina.startup.Tomcat", getClass().getClassLoader()))
        .isFalse();
    assertThat(ExpressionFactory.newInstance().getClass().getProtectionDomain()
            .getCodeSource().getLocation().toString())
        .contains("bes-lite-spring-boot-starter-11.5.0.006.jar");
    var health = http.getForEntity("/actuator/health", JsonNode.class);
    assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(health.getBody().path("status").asText()).isEqualTo("UP");
    assertThat(http.getForEntity("/api/auth/me", JsonNode.class).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
    var csrf = http.getForEntity("/api/auth/csrf", JsonNode.class);
    assertThat(csrf.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(csrf.getBody().at("/data/token").asText()).isNotBlank();
  }
}
