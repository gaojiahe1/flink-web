package com.flinkweb.service.sql.support;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.service.artifact.support.JarEntryInspector;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** UDF 元信息由静态 JAR 分析重建，用户只维护别名与业务说明；不执行用户代码。 */
@Component
public class SqlUdfSupport {
  private final JarEntryInspector inspector;

  /** 注入有界静态字节码分析器。 */
  public SqlUdfSupport(JarEntryInspector inspector) {
    this.inspector = inspector;
  }

  /** 校验选中函数并以当前 JAR 的真实类型和签名替换客户端元信息。 */
  public void canonicalize(ObjectNode runtime) {
    if (!runtime.has("udfArtifacts")) return;
    var values = runtime.path("udfArtifacts");
    if (!values.isArray() || values.size() > 4) fail("每个 SQL 最多选择 4 个 UDF JAR");
    var result = runtime.arrayNode();
    Set<String> names = new HashSet<>(), references = new HashSet<>();
    for (var value : values) {
      String reference = value.path("reference").asText();
      if (reference.isBlank()
          || !references.add(reference)
          || !value.path("functions").isArray()
          || value.path("functions").isEmpty()
          || value.path("functions").size() > 32) fail("UDF JAR 引用或函数选择无效");
      var inspected = inspector.inspect(reference);
      var target = result.addObject().put("reference", reference);
      var functions = target.putArray("functions");
      for (var selected : value.path("functions")) {
        String name = selected.path("name").asText(),
            className = selected.path("className").asText();
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")
            || !names.add(name.toLowerCase(Locale.ROOT))) fail("UDF 名称需为不重复的字母数字下划线，且以字母或下划线开头");
        String description = selected.path("description").asText();
        if (description.length() > 1000) fail("UDF 业务说明不能超过 1000 字符");
        ObjectNode found = null;
        for (var function : inspected.path("functions"))
          if (className.equals(function.path("className").asText())) found = (ObjectNode) function;
        if (found == null) fail("所选 UDF 类不存在或不可实例化，请重新识别 JAR");
        functions.add(found.deepCopy().put("name", name).put("description", description));
      }
    }
    runtime.set("udfArtifacts", result);
  }

  /** 与原附加依赖合并但不修改草稿选择，发布及验证均使用同一下载器。 */
  public void dependencies(ObjectNode runtime) {
    Set<String> references = new LinkedHashSet<>();
    for (var dependency : runtime.path("dependencyArtifactIds"))
      references.add(dependency.asText());
    for (var artifact : runtime.path("udfArtifacts"))
      references.add(artifact.path("reference").asText());
    var dependencies = runtime.putArray("dependencyArtifactIds");
    references.forEach(dependencies::add);
  }

  /** 提供不含正文和凭据的可纠正错误。 */
  private void fail(String message) {
    throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_UDF", message);
  }
}
