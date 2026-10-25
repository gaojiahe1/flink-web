package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.service.artifact.support.ArtifactReferences;
import com.flinkweb.service.sql.catalog.support.HiveCatalogConfiguration;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 验证 Hive 配置输入边界、外部实体禁用及安全协议不降级。 */
class HiveCatalogConfigurationTest {
  final ObjectMapper mapper = new ObjectMapper();
  final ArtifactReferences files = mock(ArtifactReferences.class);
  final HiveCatalogConfiguration config = new HiveCatalogConfiguration(files);

  /** 每次读取新流，模拟真实配置对象，不执行任何外部文件访问。 */
  void xml(String source) throws Exception {
    when(files.describe("file"))
        .thenReturn(mapper.createObjectNode().put("filename", "hive-site.xml"));
    when(files.open("file"))
        .thenAnswer(call -> new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
  }

  /** 配置解析保留多 URI，拒绝重复属性、XXE、超大文件和不受支持的认证协议。 */
  @Test
  void parsesSecurelyAndRejectsDowngrade() throws Exception {
    var catalog = mapper.createObjectNode().put("hiveSiteArtifactId", "file");
    String property =
        "<property><name>hive.metastore.uris</name><value>thrift://metastore:9083,thrift://backup:9083</value></property>";
    xml("<configuration>" + property + "</configuration>");
    assertThat(config.uris(config.properties(catalog))).hasSize(2);
    xml("<configuration>" + property + property + "</configuration>");
    assertThatThrownBy(() -> config.properties(catalog))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("重复");
    xml(
        "<!DOCTYPE configuration [<!ENTITY x SYSTEM"
            + " 'file:///no-read'>]><configuration>&x;</configuration>");
    assertThatThrownBy(() -> config.properties(catalog))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("安全读取");
    xml(" ".repeat(128 * 1024 + 1));
    assertThatThrownBy(() -> config.properties(catalog))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("128 KiB");
    for (String key :
        List.of(
            "hive.metastore.sasl.enabled",
            "hive.metastore.use.SSL",
            "hive.metastore.thrift.framed.transport.enabled",
            "metastore.thrift.compact.protocol.enabled"))
      assertThatThrownBy(() -> config.requireBrowsable(Map.of(key, "true")))
          .isInstanceOf(BusinessException.class);
    for (String uri :
        List.of(
            "thrift://user:pass@host:9083",
            "https://host:9083",
            "thrift://host:9083/path",
            "thrift://host:0"))
      assertThatThrownBy(() -> config.uris(Map.of("hive.metastore.uris", uri)))
          .isInstanceOf(BusinessException.class);
  }
}
