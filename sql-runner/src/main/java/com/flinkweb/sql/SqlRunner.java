package com.flinkweb.sql;

import org.apache.flink.configuration.GlobalConfiguration;
import org.apache.flink.table.api.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;
import java.util.regex.*;

/** 在 Application 主进程中注册表和视图，将全部 INSERT 合并为一个 Flink 作业。 */
public final class SqlRunner {
    private static final Pattern ENV = Pattern.compile("\\{\\{env:([A-Za-z_][A-Za-z0-9_]*)}}");

    /** 禁止实例化，仅通过入口方法启动。 */
    private SqlRunner() {}

    /** 读取平台编码的语句；沿用 Operator 注入的 Flink 配置，执行单个 StatementSet。 */
    public static void main(String[] args) {
        TableEnvironment environment =
                TableEnvironment.create(
                        EnvironmentSettings.newInstance()
                                .withConfiguration(GlobalConfiguration.loadConfiguration())
                                .inStreamingMode()
                                .build());
        prepare(environment, decode(args), System::getenv).execute();
    }

    /** 先执行 DDL，再添加 INSERT；此方法仅构建执行计划，不提交运行作业。 */
    static StatementSet prepare(
            TableEnvironment environment,
            List<String> statements,
            Function<String, String> variables) {
        StatementSet set = environment.createStatementSet();
        int inserts = 0;
        for (String raw : statements) {
            String sql = substitute(raw, variables);
            if (sql.matches("(?is)^INSERT\\s+(INTO|OVERWRITE)\\b.*")) {
                set.addInsertSql(sql);
                inserts++;
            } else if ((sql.matches("(?is)^CREATE\\s+(TEMPORARY\\s+)?(TABLE|VIEW|CATALOG|DATABASE)\\b.*") || sql.matches("(?is)^USE\\s+.*"))
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
            if (!"--statement".equals(args[i]))
                throw new IllegalArgumentException("不支持的 SQL Runner 参数");
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
