package com.flinkweb.service.sql.support;

/** 对已完成引号和注释检查的语句遮蔽字面量及引用标识符，供基础关键字规则使用。 */
public final class SqlScriptLexical {
  /** 工具类不创建实例。 */
  private SqlScriptLexical() {}

  /** 保留长度和分隔位置；双写引号属于同一个引用内容，不暴露其中的关键字。 */
  public static String maskQuoted(String sql) {
    StringBuilder masked = new StringBuilder(sql);
    char quote = 0;
    for (int i = 0; i < sql.length(); i++) {
      char c = sql.charAt(i);
      if (quote != 0) {
        masked.setCharAt(i, ' ');
        if (c == quote) {
          if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
            masked.setCharAt(++i, ' ');
          } else quote = 0;
        }
      } else if (c == '\'' || c == '"' || c == '`') {
        quote = c;
        masked.setCharAt(i, ' ');
      }
    }
    return masked.toString();
  }
}
