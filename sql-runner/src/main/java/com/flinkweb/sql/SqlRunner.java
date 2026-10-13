package com.flinkweb.sql;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.regex.*;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.GlobalConfiguration;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.table.api.*;

/** 在 Application 主进程中注册表和视图，将全部 INSERT 合并为一个 Flink 作业。 */
public final class SqlRunner {
  private static final Pattern ENV = Pattern.compile("\\{\\{env:([A-Za-z_][A-Za-z0-9_]*)}}");

  /** 禁止实例化，仅通过入口方法启动。 */
  private SqlRunner() {}

  /** 读取平台编码的语句；沿用 Operator 注入的 Flink 配置，执行单个 StatementSet。 */
  public static void main(String[] args) {
    String validation = args.length >= 2 && "--validation".equals(args[0]) ? args[1] : null;
    String[] statements = validation == null ? args : Arrays.copyOfRange(args, 2, args.length);
    TableEnvironment environment =
        TableEnvironment.create(
            EnvironmentSettings.newInstance()
                .withConfiguration(executionConfiguration(GlobalConfiguration.loadConfiguration()))
                .inStreamingMode()
                .build());
    if (validation == null) prepare(environment, decode(statements), System::getenv).execute();
    else prepareValidation(environment, decode(statements), System::getenv, validation).execute();
  }

  /** 保留业务配置；用户 JAR 与类路径沿用 Flink 提交上下文，避免磁盘 local URI 覆盖已解析的 URL。 */
  static Configuration executionConfiguration(Configuration global) {
    Configuration configuration = new Configuration(global);
    configuration.removeConfig(PipelineOptions.JARS);
    configuration.removeConfig(PipelineOptions.CLASSPATHS);
    return configuration;
  }

  /** 将写入目标替换成临时 Print 表，保留原查询的变更流，由平台按总输出限额取消。 */
  static StatementSet prepareValidation(
      TableEnvironment environment,
      List<String> statements,
      Function<String, String> variables,
      String validation) {
    if (!validation.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("验证编号无效");
    StatementSet set = environment.createStatementSet();
    String identifier =
        "(?:`(?:``|[^`])+`|[A-Za-z_][A-Za-z0-9_$]*)(?:\\s*\\.\\s*(?:`(?:``|[^`])+`|[A-Za-z_][A-Za-z0-9_$]*))*";
    Pattern insert =
        Pattern.compile(
            "(?is)^INSERT\\s+(?:INTO|OVERWRITE)\\s+("
                + identifier
                + ")(\\s*\\([^)]*\\))?\\s+(.+)$");
    int count = 0;
    for (String raw : statements) {
      String sql = substitute(raw, variables);
      Matcher match = insert.matcher(sql);
      if (match.matches()) {
        String target = match.group(1);
        org.apache.flink.table.catalog.ResolvedSchema schema =
            environment.from(target).getResolvedSchema();
        Schema.Builder printSchema = Schema.newBuilder();
        schema.getColumns().stream()
            .filter(c -> c.isPhysical())
            .forEach(c -> printSchema.column(c.getName(), c.getDataType()));
        String sink = "fw_validation_" + validation + "_" + count;
        environment.createTemporaryTable(
            sink,
            TableDescriptor.forConnector("print")
                .schema(printSchema.build())
                .option("print-identifier", "FWV_" + validation + "_" + count)
                .build());
        // INSERT OVERWRITE 在验证中变为 INTO；临时表不继承原 Sink 的连接参数与主键约束。
        String columns = match.group(2) == null ? "" : match.group(2);
        Table query = environment.sqlQuery(match.group(3));
        String view = "fw_query_" + validation + "_" + count;
        environment.createTemporaryView(view, query);
        String fields =
            query.getResolvedSchema().getColumnNames().stream()
                .map(name -> "`" + name.replace("`", "``") + "`")
                .collect(java.util.stream.Collectors.joining(", "));
        set.addInsertSql(
            "INSERT INTO `" + sink + "`" + columns + " SELECT " + fields + " FROM `" + view + "`");
        count++;
      } else if (count == 0
          && (sql.matches("(?is)^CREATE\\s+(TEMPORARY\\s+)?(TABLE|VIEW|CATALOG|DATABASE)\\b.*")
              || sql.matches("(?is)^USE\\s+.*"))) {
        environment.executeSql(sql);
      } else throw new IllegalArgumentException("验证仅支持先 CREATE/USE，再 INSERT；请检查写入目标语法");
    }
    if (count == 0) throw new IllegalArgumentException("至少需要一条 INSERT");
    return set;
  }

  /** 先执行 DDL，再添加 INSERT；此方法仅构建执行计划，不提交运行作业。 */
  static StatementSet prepare(
      TableEnvironment environment, List<String> statements, Function<String, String> variables) {
    StatementSet set = environment.createStatementSet();
    int inserts = 0;
    for (String raw : statements) {
      String sql = substitute(raw, variables);
      if (sql.matches("(?is)^INSERT\\s+(INTO|OVERWRITE)\\b.*")) {
        set.addInsertSql(sql);
        inserts++;
      } else if ((sql.matches("(?is)^CREATE\\s+(TEMPORARY\\s+)?(TABLE|VIEW|CATALOG|DATABASE)\\b.*")
              || sql.matches("(?is)^USE\\s+.*"))
          && inserts == 0) environment.executeSql(sql);
      else throw new IllegalArgumentException("仅支持先 CREATE/USE，再 INSERT");
    }
    if (inserts == 0) throw new IllegalArgumentException("至少需要一条 INSERT");
    return set;
  }

  /** 解码平台传入的独立语句参数，不在运行时再次按分号切割脚本。 */
  static List<String> decode(String[] args) {
    if (args.length == 0 || args.length % 2 != 0 || args.length > 200)
      throw new IllegalArgumentException("SQL 参数数量无效");
    List<String> result = new ArrayList<>();
    int bytes = 0;
    for (int i = 0; i < args.length; i += 2) {
      if (!"--statement".equals(args[i])) throw new IllegalArgumentException("不支持的 SQL Runner 参数");
      byte[] decoded = Base64.getDecoder().decode(args[i + 1]);
      bytes += decoded.length;
      if (bytes > 32768) throw new IllegalArgumentException("SQL 超过 32 KiB");
      result.add(new String(decoded, StandardCharsets.UTF_8));
    }
    return result;
  }

  /** 仅在单引号字符串中展开环境变量，转义单引号以避免改变 SQL 结构；不打印密钥值。 */
  static String substitute(String sql, Function<String, String> variables) {
    Matcher matcher = ENV.matcher(sql);
    StringBuffer result = new StringBuffer();
    while (matcher.find()) {
      boolean quoted = false;
      for (int i = 0; i < matcher.start(); i++)
        if (sql.charAt(i) == '\'') {
          if (quoted && i + 1 < matcher.start() && sql.charAt(i + 1) == '\'') i++;
          else quoted = !quoted;
        }
      if (!quoted) throw new IllegalArgumentException("环境变量引用必须位于 SQL 单引号字符串内");
      String value = variables.apply(matcher.group(1));
      if (value == null) throw new IllegalArgumentException("缺少环境变量：" + matcher.group(1));
      matcher.appendReplacement(result, Matcher.quoteReplacement(value.replace("'", "''")));
    }
    matcher.appendTail(result);
    return result.toString();
  }
}
