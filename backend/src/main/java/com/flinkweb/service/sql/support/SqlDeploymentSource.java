package com.flinkweb.service.sql.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.sql.SqlValidationService;
import com.flinkweb.service.sql.catalog.FlinkCatalogService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 绑定 SQL 编辑修订与发布内容，启动时拒绝未发布脚本并识别旧停止快照。 */
@Component
public class SqlDeploymentSource {
  public static final String HASH = "flink-web/sql-sha256";
  public static final String REVISION = "flink-web/sql-revision";
  private final RecordRepository records;
  private final SqlValidationService validation;
  private final FlinkCatalogService catalogs;
  private final ObjectMapper mapper;

  /** 注入元数据及本地 SQL/Catalog 编译，不连接 Flink 或执行脚本。 */
  public SqlDeploymentSource(RecordRepository records, SqlValidationService validation,
      FlinkCatalogService catalogs, ObjectMapper mapper) {
    this.records = records; this.validation = validation; this.catalogs = catalogs; this.mapper = mapper;
  }

  /** 识别平台 SQL 部署，普通 JAR 的启动策略保持原规则。 */
  public boolean isSql(JsonNode deployment) {
    return "sql".equals(deployment.at("/metadata/labels/flink-web~1job-type").asText());
  }

  /** 新部署使用原文摘要，旧部署在有编辑权限时按完整初始化与脚本参数核对。 */
  public void requirePublished(JsonNode deployment, boolean canEdit) {
    if (!isSql(deployment)) return;
    String sqlId = deployment.at("/metadata/labels/flink-web~1sql-id").asText();
    var draft = records.find("sql_jobs", sqlId);
    if (draft == null) return;
    String published = deployment.at("/metadata/annotations").path(HASH).asText();
    boolean same = published.isBlank();
    if (!published.isBlank()) same = published.equals(hash(draft.path("sql").asText()));
    else if (canEdit) {
      try {
        var expected = mapper.createArrayNode();
        var runtime = ((ObjectNode) draft.path("runtime")).deepCopy();
        for (String statement : validation.validate(catalogs.prepare(runtime) + draft.path("sql").asText()).statements()) {
          expected.add("--statement").add(Base64.getEncoder().encodeToString(statement.getBytes(StandardCharsets.UTF_8)));
        }
        same = expected.equals(deployment.at("/spec/job/args"));
      } catch (BusinessException error) { same = false; }
    }
    if (!same) throw new BusinessException(HttpStatus.CONFLICT, "SQL_NOT_PUBLISHED",
        "SQL 草稿已修改但尚未发布，请在 SQL 开发点击“生成部署草稿”或“部署并启动”，再启动最新 SQL");
  }

  /** SQL 参数是不可变字符串数组；与停止快照不同则不能继续沿用旧脚本。 */
  public boolean changed(JsonNode published, JsonNode previous) {
    return isSql(published) && previous != null && !previous.isMissingNode()
        && !published.at("/spec/job/args").equals(previous.at("/spec/job/args"));
  }

  /** 发布注解便于从集群 YAML 核对原始 SQL 修订及 UTF-8 摘要，不包含脚本明文。 */
  public static void stamp(ObjectNode resource, long revision, String sql) {
    resource.withObject("/metadata/annotations").put(REVISION, Long.toString(revision)).put(HASH, hash(sql));
  }

  /** 计算确定性的原始 SQL UTF-8 摘要。 */
  public static String hash(String sql) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8))); }
    catch (Exception error) { throw new IllegalStateException(error); }
  }
}
