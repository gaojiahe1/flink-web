package com.flinkweb.service.catalog.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.security.permission.SecretCipher;
import com.flinkweb.service.audit.AuditService;
import com.flinkweb.service.catalog.CatalogService;
import com.flinkweb.service.datasource.DataSourceService;
import java.net.URI;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 维护模板、镜像、配置及制品目录，负责输入校验、密文存储和输出脱敏。 */
@Service
public class CatalogServiceImpl implements CatalogService {
  private static final Set<String> COLLECTIONS =
      Set.of("templates", "images", "configs", "artifacts", "datasources");
  private final RecordRepository store;
  private final SecretCipher cipher;
  private final AuditService audit;
  private final DataSourceService dataSources;

  /** 注入元数据仓库、密钥加密器和审计服务。 */
  public CatalogServiceImpl(
      RecordRepository store,
      SecretCipher cipher,
      AuditService audit,
      DataSourceService dataSources) {
    this.store = store;
    this.cipher = cipher;
    this.audit = audit;
    this.dataSources = dataSources;
  }

  /** 校验目录类型并映射到允许访问的元数据集合。 */
  private void collection(String type) {
    if (!COLLECTIONS.contains(type))
      throw new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", "类型不存在");
  }

  /** 查询目录记录并对敏感配置脱敏。 */
  public List<ObjectNode> list(String type) {
    collection(type);
    return store.list(type).stream().map(this::redact).toList();
  }

  /** 读取指定目录记录并返回可展示的字段。 */
  public ObjectNode get(String type, String id) {
    collection(type);
    return redact(store.require(type, id));
  }

  /** 复制记录并隐藏密文及其他不应返回前端的字段。 */
  private ObjectNode redact(ObjectNode node) {
    var copy = node.deepCopy();
    if (copy.has("encryptedValue")) {
      copy.remove("encryptedValue");
      copy.put("hasValue", true);
    }
    copy.remove("digest");
    return copy;
  }

  /** 按目录类型校验字段，处理配置加密后以修订号保存并审计。 */
  @Transactional
  public ObjectNode save(String type, String id, ObjectNode input) {
    collection(type);
    if (!id.matches("[a-z0-9][a-z0-9_.-]{0,100}")) fail("标识只允许小写字母、数字、点、下划线和连字符，最长 101 位");
    long revision = input.path("revision").asLong();
    var old = store.find(type, id);
    ObjectNode node =
        input
            .objectNode()
            .put("name", input.path("name").asText(id))
            .put("description", input.path("description").asText(""));
    switch (type) {
      case "datasources" -> {
        if (old != null
            && !old.path("connectorType").asText().equals(input.path("connectorType").asText())
            && store.list("flink_catalogs").stream()
                .anyMatch(c -> id.equals(c.path("dataSourceId").asText())))
          fail("已被 JDBC Catalog 引用的数据源不能修改为其他类型");
        node.setAll(dataSources.validate(input));
      }
      case "configs" -> {
        String kind = input.path("kind").asText("plain");
        if (!Set.of("plain", "secret", "secretRef").contains(kind)) fail("配置类型无效");
        node.put("kind", kind);
        if (kind.equals("secret")) {
          String value = input.path("value").asText("");
          if (!value.isEmpty()) node.put("encryptedValue", cipher.encrypt(id, value));
          else if (old != null && old.has("encryptedValue"))
            node.set("encryptedValue", old.get("encryptedValue"));
          else fail("请输入密钥值");
        } else if (kind.equals("plain")) node.set("value", input.path("value"));
        else {
          String secret = input.path("secretName").asText(), key = input.path("secretKey").asText();
          if (!secret.matches("[a-z0-9]([-a-z0-9.]*[a-z0-9])?") || !key.matches("[a-zA-Z0-9._-]+"))
            fail("Secret 名称或键无效");
          node.put("secretName", secret).put("secretKey", key);
        }
      }
      case "templates" -> {
        String yaml = input.path("yaml").asText();
        if (yaml.isBlank() || yaml.length() > 200000) fail("模板 YAML 不能为空且不超过 200KB");
        node.put("yaml", yaml);
      }
      case "images" -> {
        String repository = input.path("repository").asText(),
            version = input.path("version").asText();
        if (!repository.matches("[a-zA-Z0-9][a-zA-Z0-9.:/_-]*")) fail("repository: 镜像仓库格式无效");
        if (!version.matches("[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}")) fail("version: 镜像版本格式无效");
        var match =
            java.util.regex.Pattern.compile(
                    "^(1\\.(?:16|17|18|19|20)|2\\.[0-3])(\\.[0-9]+)?(?:[-_].*)?$")
                .matcher(version);
        String flinkVersion;
        if (match.matches())
          flinkVersion = match.group(1) + (match.group(2) == null ? "" : match.group(2));
        else if (!input.path("flinkVersion").asText().isBlank())
          flinkVersion = input.path("flinkVersion").asText();
        else if (old != null && old.path("version").asText().equals(version))
          flinkVersion = old.path("flinkVersion").asText();
        else {
          fail("version: 镜像版本须以 Flink 版本开头，例如 1.20.5-scala_2.12");
          return null;
        }
        node.put("name", id)
            .put("repository", repository)
            .put("version", version)
            .put("flinkVersion", flinkVersion)
            .put("javaVersion", input.path("javaVersion").asText());
        String downloader =
            input
                .path("downloaderImage")
                .asText(old == null ? "" : old.path("downloaderImage").asText())
                .trim();
        if (!downloader.isEmpty() && !downloader.matches("[a-zA-Z0-9][a-zA-Z0-9.:/_@-]*"))
          fail("downloaderImage: 下载器镜像地址无效");
        node.put("downloaderImage", downloader);
        // 编辑旧记录时保留既有拉取凭据；新表单不再要求填写。
        if (old != null && old.has("pullSecret")) node.set("pullSecret", old.get("pullSecret"));
      }
      case "artifacts" -> {
        URI uri;
        try {
          uri = URI.create(input.path("uri").asText());
        } catch (Exception e) {
          fail("制品地址无效");
          return null;
        }
        if (!Set.of("https", "http", "s3").contains(uri.getScheme())
            || uri.getHost() == null
            || uri.getUserInfo() != null
            || uri.getQuery() != null
            || uri.getFragment() != null) fail("只允许不含凭证和查询参数的 HTTP/HTTPS/S3 地址");
        if (!input.path("sha256").asText().matches("[a-fA-F0-9]{64}")) fail("必须提供 SHA-256 校验值");
        String category = input.path("category").asText("shared");
        if (!Set.of("main", "dependency", "shared").contains(category)) fail("制品目录类型无效");
        node.put("category", category);
        String filename = input.path("filename").asText();
        if (category.equals("main") && !filename.toLowerCase(Locale.ROOT).endsWith(".jar"))
          fail("主程序必须为 JAR 文件");
        if (!filename.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}")) fail("文件名无效");
        for (String field : List.of("uri", "sha256", "filename", "credentialRef"))
          node.set(field, input.path(field));
      }
    }
    var result = store.save(type, id, node, revision);
    audit.record("CATALOG_SAVE", type + "/" + id, "SUCCESS");
    return redact(result);
  }

  /** 按修订号删除目录记录并保存审计结果。 */
  @Transactional
  public void delete(String type, String id, long revision) {
    collection(type);
    if (type.equals("datasources")
        && store.list("flink_catalogs").stream()
            .anyMatch(c -> id.equals(c.path("dataSourceId").asText())))
      throw new BusinessException(
          HttpStatus.CONFLICT, "DATASOURCE_IN_USE", "数据源正在被 Catalog 引用，请先修改或删除关联 Catalog");
    store.delete(type, id, revision);
    audit.record("CATALOG_DELETE", type + "/" + id, "SUCCESS");
  }

  /** 生成目录配置不合法的业务异常。 */
  private void fail(String message) {
    throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_CATALOG", message);
  }
}
