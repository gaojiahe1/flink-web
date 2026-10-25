package com.flinkweb.service.sql.catalog.support;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.service.artifact.support.ArtifactReferences;
import java.io.*;
import java.net.URI;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 从受控配置文件读取 Hive 客户端参数，禁用外部实体，不把 Hadoop/Hive 加载到平台。 */
@Component
public class HiveCatalogConfiguration {
  private final ArtifactReferences artifacts;

  /** 注入统一制品读取边界。 */
  public HiveCatalogConfiguration(ArtifactReferences artifacts) {
    this.artifacts = artifacts;
  }

  /** 提取非敏感连接字段；原来的容器挂载目录仍可兼容，但不能视为已核验文件。 */
  public Map<String, String> properties(ObjectNode catalog) {
    String reference = catalog.path("hiveSiteArtifactId").asText();
    if (reference.isBlank()) {
      if (catalog.path("metastoreUris").asText().isBlank())
        fail("请上传 hive-site.xml，或为已有挂载配置填写 Metastore URI");
      return Map.of("hive.metastore.uris", catalog.path("metastoreUris").asText());
    }
    if (!artifacts.describe(reference).path("filename").asText().equals("hive-site.xml"))
      fail("Hive 配置文件必须命名为 hive-site.xml");
    try (InputStream source = artifacts.open(reference)) {
      byte[] bytes = source.readNBytes(128 * 1024 + 1);
      if (bytes.length > 128 * 1024) fail("hive-site.xml 不能超过 128 KiB");
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      var builder = factory.newDocumentBuilder();
      builder.setErrorHandler(
          new org.xml.sax.helpers.DefaultHandler() {
            /** XML 错误不输出用户文件正文。 */
            @Override
            public void fatalError(org.xml.sax.SAXParseException ex)
                throws org.xml.sax.SAXException {
              throw ex;
            }
          });
      var doc = builder.parse(new ByteArrayInputStream(bytes));
      if (!doc.getDocumentElement().getTagName().equals("configuration"))
        fail("Hive 配置根节点必须为 configuration");
      Map<String, String> values = new HashMap<>();
      var entries = doc.getElementsByTagName("property");
      for (int i = 0; i < entries.getLength(); i++) {
        var element = (org.w3c.dom.Element) entries.item(i);
        var names = element.getElementsByTagName("name");
        var data = element.getElementsByTagName("value");
        if (names.getLength() != 1 || data.getLength() != 1)
          fail("Hive property 需包含唯一 name 和 value");
        String name = names.item(0).getTextContent().trim(),
            value = data.item(0).getTextContent().trim();
        if (values.putIfAbsent(name, value) != null) fail("Hive 配置包含重复属性");
      }
      uris(values);
      return values;
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      fail("无法安全读取 hive-site.xml，请检查 XML 格式和文件权限");
      return Map.of();
    }
  }

  /** 只接受最多三个无凭据的 Thrift 地址，多个 URI 依次尝试。 */
  public List<URI> uris(Map<String, String> properties) {
    String text =
        properties.getOrDefault(
            "hive.metastore.uris", properties.getOrDefault("metastore.thrift.uris", ""));
    if (text.isBlank() || text.length() > 1500) fail("hive-site.xml 必须配置 hive.metastore.uris");
    List<URI> result = new ArrayList<>();
    for (String part : text.split(",")) {
      try {
        URI uri = URI.create(part.trim());
        if (!"thrift".equals(uri.getScheme())
            || uri.getHost() == null
            || uri.getUserInfo() != null
            || uri.getQuery() != null
            || uri.getFragment() != null
            || !(uri.getPath() == null || uri.getPath().isEmpty() || uri.getPath().equals("/"))
            || uri.getPort() == 0
            || uri.getPort() > 65535) fail("Metastore URI 需为 thrift://host:port，不能包含凭据、路径或参数");
        result.add(uri);
      } catch (IllegalArgumentException ex) {
        fail("Metastore URI 格式无效");
      }
    }
    if (result.size() > 3) fail("最多支持三个 Metastore URI");
    return result;
  }

  /** 元数据客户端不降级 SASL/TLS 或 Compact 协议，运行时仍由上传配置及插件决定。 */
  public void requireBrowsable(Map<String, String> values) {
    for (String key :
        List.of(
            "hive.metastore.sasl.enabled",
            "hive.metastore.use.SSL",
            "hive.metastore.thrift.compact.protocol.enabled",
            "hive.metastore.thrift.framed.transport.enabled",
            "metastore.thrift.framed.transport.enabled",
            "metastore.thrift.compact.protocol.enabled",
            "metastore.use.SSL",
            "metastore.sasl.enabled"))
      if (Boolean.parseBoolean(values.getOrDefault(key, "false")))
        fail("此 Hive 配置启用了 SASL/TLS/Compact；平台当前只读浏览不支持该协议，不能降级连接。请在匹配的 Flink 运行环境验证");
  }

  /** 返回输入错误，不带文件内容或密钥。 */
  private void fail(String message) {
    throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_HIVE_CONFIGURATION", message);
  }
}
