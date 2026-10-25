package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.service.artifact.support.JarEntryInspector;
import com.flinkweb.service.sql.support.SqlUdfSupport;
import org.junit.jupiter.api.Test;

/** 验证客户端无法伪造 UDF 类型/签名，以及选中函数的去重和依赖装配。 */
class SqlUdfSupportTest {
  /** 两个真实函数可绑定独立名称；不存在的类、重复名称和伪造元信息都不能绕过服务端。 */
  @Test
  void rebuildsMetadataAndDependencies() throws Exception {
    var mapper = new ObjectMapper();
    var inspector = mock(JarEntryInspector.class);
    var functions = mapper.createObjectNode();
    var real = functions.putArray("functions");
    real.addObject()
        .put("className", "example.Trim")
        .put("kind", "SCALAR")
        .putArray("signatures")
        .add("eval(String)");
    real.addObject()
        .put("className", "example.Upper")
        .put("kind", "SCALAR")
        .putArray("signatures")
        .add("eval(String)");
    when(inspector.inspect("udf.jar")).thenReturn(functions);
    var support = new SqlUdfSupport(inspector);
    var runtime = mapper.createObjectNode();
    var selected =
        runtime
            .putArray("udfArtifacts")
            .addObject()
            .put("reference", "udf.jar")
            .putArray("functions");
    selected
        .addObject()
        .put("className", "example.Trim")
        .put("name", "trim_text")
        .put("description", "去除空格")
        .put("kind", "FORGED");
    selected.addObject().put("className", "example.Upper").put("name", "upper_text");
    support.canonicalize(runtime);
    assertThat(runtime.at("/udfArtifacts/0/functions/0/kind").asText()).isEqualTo("SCALAR");
    assertThat(runtime.at("/udfArtifacts/0/functions/0/description").asText()).isEqualTo("去除空格");
    runtime.putArray("dependencyArtifactIds").add("driver.jar").add("udf.jar");
    support.dependencies(runtime);
    support.dependencies(runtime);
    assertThat(runtime.path("dependencyArtifactIds")).hasSize(2);
    ((ObjectNode) runtime.at("/udfArtifacts/0/functions/1")).put("name", "TRIM_TEXT");
    assertThatThrownBy(() -> support.canonicalize(runtime))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不重复");
    ((ObjectNode) runtime.at("/udfArtifacts/0/functions/1"))
        .put("name", "upper_text")
        .put("className", "example.Unknown");
    assertThatThrownBy(() -> support.canonicalize(runtime))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("不存在");
  }
}
