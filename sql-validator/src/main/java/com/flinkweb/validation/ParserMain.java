package com.flinkweb.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.flink.sql.parser.impl.FlinkSqlParserImpl;
import java.util.Properties;

/** 独立 JVM 中运行指定 Flink 版本的 SQL Parser，避免不同 Calcite 依赖污染平台。 */
public final class ParserMain {
    /** 从标准输入读取有限大小的脚本数组，向标准输出返回单个 JSON 校验结果。 */
    public static void main(String[] args) throws Exception {
        var mapper=new ObjectMapper();
        var properties=new Properties();
        try(var stream=ParserMain.class.getResourceAsStream("/validator.properties")){properties.load(stream);}
        var result=mapper.createObjectNode().put("version",properties.getProperty("flink.version"));
        var input=mapper.readTree(System.in.readNBytes(131072));
        int index=0;
        try {
            var config=SqlParser.config().withParserFactory(FlinkSqlParserImpl.FACTORY)
                .withQuoting(Quoting.BACK_TICK).withUnquotedCasing(Casing.UNCHANGED).withQuotedCasing(Casing.UNCHANGED).withIdentifierMaxLength(256);
            for(var statement:input.path("statements")){index++;SqlParser.create(statement.asText(),config).parseStmt();}
            result.put("valid",true);
        } catch(SqlParseException ex) {
            var pos=ex.getPos();
            result.put("valid",false).put("statement",index).put("line",pos.getLineNum()).put("column",pos.getColumnNum());
            String message=ex.getMessage();result.put("message",message.substring(0,Math.min(800,message.length())));
        } catch(Exception ex) {result.put("valid",false).put("statement",index).put("message","SQL Parser 无法解析此语句");}
        System.out.print(mapper.writeValueAsString(result));
    }
}
