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
    List<String> targets = new ArrayList<>();
    for (String raw : statements) {
      Matcher target = insert.matcher(raw);
      if (target.matches()) targets.add(target.group(1));
    }
    int count = 0;
    for (String raw : statements) {
      if (raw.matches("(?is)^CREATE\\s+(?:TEMPORARY\\s+)?TABLE\\b.*\\bAS\\s*\\(?\\s*(?:SELECT|WITH|VALUES)\\b.*"))
        throw new IllegalArgumentException("验证不执行 CREATE TABLE AS 查询，请改为源表定义加 SELECT");
      // 先剥离目标表连接参数，再展开变量；验证不要求原 Sink 的地址、驱动或凭据。
      String sql = substitute(validationDeclaration(raw, targets, environment), variables);
      Matcher match = insert.matcher(sql);
      boolean writing = match.matches();
      if (writing || sql.matches("(?is)^(SELECT|WITH|VALUES)\\b.*")) {
        String target = writing ? match.group(1) : "查询结果 " + (count + 1);
        Table query = environment.sqlQuery(writing ? match.group(3) : sql);
        org.apache.flink.table.catalog.ResolvedSchema schema = writing
            ? environment.from(target).getResolvedSchema() : query.getResolvedSchema();
        List<org.apache.flink.table.catalog.Column> physical = new ArrayList<>();
        for (org.apache.flink.table.catalog.Column column : schema.getColumns())
          if (column.isPhysical()) physical.add(column);
        List<String> mapping = new ArrayList<>();
        if (writing && match.group(2) != null) {
          Matcher columns = Pattern.compile("`((?:``|[^`])+)`|([A-Za-z_][A-Za-z0-9_$]*)").matcher(match.group(2));
          while (columns.find()) mapping.add(columns.group(1) != null ? columns.group(1).replace("``", "`") : columns.group(2));
        } else for (org.apache.flink.table.catalog.Column column : physical) mapping.add(column.getName());
        if (mapping.size() != query.getResolvedSchema().getColumnCount())
          throw new IllegalArgumentException("输出字段数量与查询不一致");
        if (new HashSet<>(mapping).size() != mapping.size()) throw new IllegalArgumentException("输出字段重复");
        for (String column : mapping)
          if (!schema.getColumn(column).isPresent()) throw new IllegalArgumentException("输出字段不存在：" + column);
        String[] aliases = new String[mapping.size()];
        for (int i = 0; i < aliases.length; i++) aliases[i] = "fw_column_" + i;
        query = query.as(aliases[0], Arrays.copyOfRange(aliases, 1, aliases.length));
        String view = "fw_query_" + validation + "_" + count;
        environment.createTemporaryView(view, query);
        List<String> names = new ArrayList<>(), values = new ArrayList<>();
        for (org.apache.flink.table.catalog.Column column : physical) {
          names.add(literal(column.getName()));
          int index = mapping.indexOf(column.getName());
          String field = index < 0 ? "NULL" : "`" + aliases[index] + "`";
          String type = column.getDataType().getLogicalType().copy(true).asSerializableString();
          values.add("CAST(CAST(" + field + " AS " + type + ") AS STRING)");
        }
        List<String> keys = new ArrayList<>();
        if (schema.getPrimaryKey().isPresent())
          for (String key : schema.getPrimaryKey().get().getColumns()) keys.add(literal(key));
        String payload = "JSON_OBJECT('columns' VALUE JSON_ARRAY(" + String.join(",", names)
            + "), 'cells' VALUE JSON_ARRAY(" + String.join(",", values)
            + " NULL ON NULL), 'keys' VALUE JSON_ARRAY(" + String.join(",", keys)
            + "), 'target' VALUE " + literal(target) + ")";
        String sink = "fw_validation_" + validation + "_" + count;
        environment.createTemporaryTable(
            sink,
            TableDescriptor.forConnector("print")
                .schema(Schema.newBuilder().column("fw_data", DataTypes.STRING()).build())
                .option("print-identifier", "FWV_" + validation + "_" + count)
                .build());
        set.addInsertSql("INSERT INTO `" + sink + "` SELECT " + payload + " FROM `" + view + "`");
        count++;
      } else if (count == 0
          && (sql.matches("(?is)^CREATE\\s+(TEMPORARY\\s+)?(TABLE|VIEW|CATALOG|DATABASE)\\b.*")
              || sql.matches("(?is)^USE\\s+.*"))) {
        environment.executeSql(sql);
      } else throw new IllegalArgumentException("验证仅支持先 CREATE/USE，再 INSERT 或 SELECT");
    }
    if (count == 0) throw new IllegalArgumentException("至少需要一条 INSERT 或 SELECT");
    return set;
  }

  /** 只替换显式写入目标的顶层 WITH 选项，字段和主键保留，不解析原 Sink 的连接器。 */
  static String validationDeclaration(String sql, List<String> targets, TableEnvironment environment) {
    String identifier = "(?:`(?:``|[^`])+`|[A-Za-z_][A-Za-z0-9_$]*)(?:\\s*\\.\\s*(?:`(?:``|[^`])+`|[A-Za-z_][A-Za-z0-9_$]*))*";
    Matcher table = Pattern.compile("(?is)^CREATE\\s+(?:TEMPORARY\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(" + identifier + ")\\s*\\(").matcher(sql);
    if (!table.find()) return sql;
    boolean sink = false;
    for (String target : targets)
      if (qualified(target, environment).equals(qualified(table.group(1), environment))) sink = true;
    if (!sink) return sql;
    int depth = 1;
    char quote = 0;
    for (int i = table.end(); i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (quote != 0) {
        if (c == quote) {
          if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) i++;
          else quote = 0;
        }
      } else if (c == '\'' || c == '"' || c == '`') quote = c;
      else if (c == '(') depth++;
      else if (c == ')' && --depth == 0) return sql.substring(0, i + 1) + " WITH ('connector'='blackhole')";
    }
    throw new IllegalArgumentException("验证输出表结构不完整");
  }

  /** 按当前目录解析限定名，反引号内的点仍属于单个名称。 */
  private static List<String> qualified(String text, TableEnvironment environment) {
    List<String> parts = new ArrayList<>();
    Matcher matcher = Pattern.compile("`((?:``|[^`])+)`|([A-Za-z_][A-Za-z0-9_$]*)").matcher(text);
    while (matcher.find()) parts.add(matcher.group(1) == null ? matcher.group(2) : matcher.group(1).replace("``", "`"));
    if (parts.size() == 1) parts.add(0, environment.getCurrentDatabase());
    if (parts.size() == 2) parts.add(0, environment.getCurrentCatalog());
    return parts;
  }

  /** 将元数据名称编码为 SQL 字符串常量，不允许字段名改变查询结构。 */
  private static String literal(String value) {
    return "'" + value.replace("'", "''") + "'";
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
