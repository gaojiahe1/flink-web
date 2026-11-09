package com.flinkweb;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.service.ai.support.*;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.repository.metadata.RecordRepository;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
/** 真实HTTP安全链验证，所有元数据及凭据仅存在独立测试库。 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@WithMockUser(username="admin",roles="ADMIN")
class AiPlatformApiTest {
  static final Path DB;
  static {try{DB=Files.createTempDirectory("mcp-platform-test-").resolve("test.db");}catch(Exception ex){throw new RuntimeException(ex);}}
  @Autowired AiPlatformApiCatalog catalog;@Autowired AiApiTransport transport;@Autowired ObjectMapper mapper;@Autowired RecordRepository records;@Autowired AiKubernetesTools k8s;
  @MockitoBean KubernetesClient kubernetes;
  /** 隔离真实库和启动密钥，不访问用户数据库或集群。 */
  @DynamicPropertySource static void config(DynamicPropertyRegistry p){p.add("platform.storage.local-path",()->DB.toString());p.add("platform.security.password",()->"Test-password-12345");p.add("platform.security.master-key",()->"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");}
  /** 注册覆盖实际业务路由，认证/权限和递归编排不会被误注册。 */
  @Test void registryUsesRealRoutesAndExcludesSecurity(){var ops=catalog.operations().values();assertThat(ops.size()).isGreaterThan(70);assertThat(ops).noneMatch(o->o.handler().getBeanType().getPackageName().endsWith(".permission"));assertThat(ops).anyMatch(o->o.path().equals("/api/jobs")&&o.method().equals("POST"));assertThat(ops).noneMatch(o->o.path().contains("/harness")||o.path().contains("/mcp")||o.path().contains("api-proposals"));assertThat(catalog.tools().toString()).contains("parameters","requiresConfirmation");}
  /** 本机委托仍通过实际控制器，写入需确认且重试不重复新增。 */
  @Test void realHttpReadAndConfirmedWriteOnlyOnce(){var read=find("GET","/api/system");assertThat(transport.call(read.name(),mapper.createObjectNode(),"","").path("httpStatus").asInt()).isEqualTo(200);var op=find("PUT","/api/catalog/{type}/{id}");var args=mapper.createObjectNode();args.putObject("path").put("type","configs").put("id","qa-plain");args.putObject("body").put("kind","plain").put("name","test").put("value","42").put("revision",0);var pending=transport.call(op.name(),args,"","");String id=pending.path("proposalId").asText();assertThat(records.find("configs","qa-plain")).isNull();assertThatThrownBy(()->transport.execute(id,false)).hasMessageContaining("审阅");var done=transport.execute(id,true);assertThat(done.path("state").asText()).isEqualTo("COMPLETED");assertThat(records.require("configs","qa-plain").path("value").asText()).isEqualTo("42");long revision=records.require("configs","qa-plain").path("revision").asLong();transport.execute(id,true);assertThat(records.require("configs","qa-plain").path("revision").asLong()).isEqualTo(revision);}
  /** 路径穿越、非声明参数、其他用户提案和伪造内网令牌不可授权。 */
  @Test void rejectsTraversalAndCrossOwner(){var op=find("GET","/api/jobs/{id}");var args=mapper.createObjectNode();args.putObject("path").put("id","../users");assertThatThrownBy(()->transport.call(op.name(),args,"","")).hasMessageContaining("路径");var row=records.save("ai_api_proposals",UUID.randomUUID().toString(),mapper.createObjectNode().put("owner","other"),0);assertThatThrownBy(()->transport.proposal(row.path("id").asText())).hasMessageContaining("无权");}
  /** 调用前重新核对启用状态及当前角色，不相信已发现目录的授权。 */
  @Test void changedRoleInvalidatesCachedTools(){var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();try{org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin","",List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OPERATOR"))));assertThatThrownBy(()->catalog.require(find("PUT","/api/catalog/{type}/{id}").name())).hasMessageContaining("角色");}finally{org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);}}
  /** K8s仅固定namespace白名单，并舍弃env和注解。 */
  @Test void kubernetesProjectionAndSqlSkill(){when(kubernetes.core()).thenReturn("/api/v1/namespaces/flink-jobs");var data=mapper.createObjectNode();var pod=data.putArray("items").addObject();pod.putObject("metadata").put("name","jm").putObject("annotations").put("sensitive","hidden");pod.putObject("spec").putArray("containers").addObject().put("env","hidden");when(kubernetes.call(eq("GET"),eq("/api/v1/namespaces/flink-jobs/pods"),isNull())).thenReturn(data);assertThat(k8s.read("k8s_list",mapper.createObjectNode().put("kind","pods")).toString()).doesNotContain("hidden","annotations","env");assertThatThrownBy(()->k8s.read("k8s_get",mapper.createObjectNode().put("kind","secrets").put("name","secret"))).hasMessageContaining("不支持");assertThat(AiSqlGenerationSkill.guide(mapper,"1.20.5","window").toString()).contains("catalog_metadata","sql_propose","Watermark","静态Parser");}
  /** 猜测的内部请求头不能跳过登录或CSRF。 */
  @Test void forgedDelegationIsRejected()throws Exception{var client=java.net.http.HttpClient.newHttpClient();var response=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+port+"/api/system")).header("X-Flink-Internal-Call","forged").build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertThat(response.statusCode()).isIn(401,403);}
  @org.springframework.boot.test.web.server.LocalServerPort int port;
  /** 已取消轮次的提案即使通过旧审阅链接也不能执行。 */
  @Test void cancelledTurnCannotExecuteProposal(){var op=find("PUT","/api/catalog/{type}/{id}");var args=mapper.createObjectNode();args.putObject("path").put("type","configs").put("id","cancelled-write");args.putObject("body").put("kind","plain").put("value","x");String sessionId=UUID.randomUUID().toString(),turnId=UUID.randomUUID().toString();records.save("ai_harness_sessions",sessionId,mapper.createObjectNode().put("owner","admin").put("epoch","e").put("sequence",1),0);records.save("ai_harness_turns",turnId,mapper.createObjectNode().put("epoch","e").put("sequence",1).put("state","CANCELED"),0);var proposal=transport.call(op.name(),args,sessionId,turnId);assertThatThrownBy(()->transport.execute(proposal.path("proposalId").asText(),true)).hasMessageContaining("取消");assertThat(records.find("configs","cancelled-write")).isNull();}
  /** 泛型Catalog路径解析后仍禁止通过value字段写入明文密钥。 */
  @Test void genericCatalogCannotSmuggleSecretValues(){var args=mapper.createObjectNode();args.putObject("path").put("type","configs").put("id","secret-qa");args.putObject("body").put("kind","secret").put("value","synthetic-unregistered-value");assertThatThrownBy(()->transport.call(find("PUT","/api/catalog/{type}/{id}").name(),args,"","")).hasMessageContaining("密钥");assertThat(records.find("configs","secret-qa")).isNull();}
  /** 查找登记的真实路由，不依赖生成工具名称的实现细节。 */
  private AiPlatformApiCatalog.Operation find(String method,String path){return catalog.operations().values().stream().filter(o->o.method().equals(method)&&o.path().equals(path)).findFirst().orElseThrow();}
}
