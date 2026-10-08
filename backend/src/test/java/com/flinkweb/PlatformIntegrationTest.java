package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.dto.job.creation.DraftRequest;
import com.flinkweb.dto.job.management.AdoptJobRequest;
import com.flinkweb.dto.sql.SqlDraftRequest;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.catalog.CatalogService;
import com.flinkweb.service.job.creation.FormService;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.job.creation.YamlService;
import com.flinkweb.service.job.creation.support.VariableRenderer;
import com.flinkweb.service.job.management.JobAdoptionService;
import com.flinkweb.service.job.management.JobOwnershipService;
import com.flinkweb.service.sql.SqlJobService;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 验证登录权限、草稿、配置、操作幂等和既有作业纳管的业务闭环。 */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformIntegrationTest {
  static final Path DATABASE;

  static {
    try {
      DATABASE = Files.createTempDirectory("flink-web-test-").resolve("test.db");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /** 为集成测试提供独立 SQLite 文件及测试专用管理员和主密钥。 */
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("platform.artifacts.downloader-image", () -> "registry.test/downloader:kylin-sp3");
    registry.add("platform.storage.type", () -> "local");
    registry.add("platform.storage.local-path", () -> DATABASE.toString());
    registry.add("platform.storage.initialize", () -> true);
    registry.add("platform.security.password", () -> "Test-password-12345");
    registry.add(
        "platform.security.master-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper mapper;
  @Autowired RecordRepository records;
  @Autowired YamlService yaml;
  @Autowired VariableRenderer renderer;
  @Autowired CatalogService catalogs;
  @Autowired FormService forms;
  @MockitoBean KubernetesClient kubernetes;

  /** 生成指定名称的最小 FlinkDeployment YAML 测试样例。 */
  String source(String name) {
    return """
           apiVersion: flink.apache.org/v1beta1
           kind: FlinkDeployment
           metadata:
             name: %s
           spec:
             image: registry.example/flink:1.20
             flinkVersion: v1_20
             job:
               jarURI: local:///opt/flink/usrlib/job.jar
               parallelism: 1
               upgradeMode: savepoint
               state: running
           """
        .formatted(name);
  }

  /** 验证不安全 YAML、重复键以及跨 Namespace 配置均被拒绝。 */
  @Test
  void parserRejectsDuplicateKeysTagsAndOtherNamespace() {
    assertThatThrownBy(() -> yaml.parse("kind: one\nkind: two"))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> yaml.parse("!!java.net.URL [http://example.com]"))
        .isInstanceOf(BusinessException.class);
    var node = yaml.parse(source("sample"));
    ((ObjectNode) node.get("metadata")).put("namespace", "other");
    assertThatThrownBy(() -> yaml.validate(node, "sample")).isInstanceOf(BusinessException.class);
    var pod = yaml.parse(source("sample"));
    pod.withObject("/spec")
        .withObject("/podTemplate")
        .withObject("/metadata")
        .put("namespace", "other");
    assertThatThrownBy(() -> yaml.validate(pod, "sample")).isInstanceOf(BusinessException.class);
    var config = yaml.parse(source("sample"));
    config
        .withObject("/spec")
        .withObject("/flinkConfiguration")
        .put("kubernetes.namespace", "other");
    assertThatThrownBy(() -> yaml.validate(config, "sample")).isInstanceOf(BusinessException.class);
  }

  /** 验证默认 S3 状态路径、checkpoint 保留数量及变量类型保持。 */
  @Test
  void statePolicyAndTypedVariables() {
    var node = yaml.validate(yaml.parse(source("sample")), "sample");
    assertThat(node.at("/spec/flinkConfiguration").size()).isZero();
    node.withObject("/spec/flinkConfiguration")
        .put("state.checkpoints.num-retained", "7")
        .put("state.checkpoints.dir", "hdfs:///jobs/checkpoints")
        .put("execution.checkpointing.interval", "90 s")
        .put("restart-strategy.type", "fixed-delay")
        .put("restart-strategy.fixed-delay.attempts", "4");
    var validated = yaml.validate(node, "sample");
    assertThat(validated.at("/spec/flinkConfiguration/state.checkpoints.num-retained").asText())
        .isEqualTo("7");
    assertThat(validated.at("/spec/flinkConfiguration/state.checkpoints.dir").asText())
        .isEqualTo("hdfs:///jobs/checkpoints");
    String config = "parallel-" + UUID.randomUUID();
    catalogs.save(
        "configs", config, mapper.createObjectNode().put("kind", "plain").put("value", 3));
    ((ObjectNode) node.at("/spec/job")).put("parallelism", "{{config:" + config + "}}");
    assertThat(renderer.render(node).deployment().at("/spec/job/parallelism").asInt()).isEqualTo(3);
  }

  /** 验证敏感配置密文持久化，部署中仅通过 Secret 引用使用。 */
  @Test
  @WithMockUser(roles = "ADMIN")
  void secretIsEncryptedAndOnlyBecomesSecretRef() throws Exception {
    String id = "secret-" + UUID.randomUUID();
    var credentialSession = new org.springframework.mock.web.MockHttpSession();
    var envelope =
        CredentialEnvelopeFixture.encrypt(
            transport.issue(credentialSession, "config-secret", id), "super-sensitive-value");
    mvc.perform(
            put("/api/catalog/configs/" + id)
                .session(credentialSession)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper
                        .createObjectNode()
                        .put("kind", "secret")
                        .put("value", envelope)
                        .toString()))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("super-sensitive-value"))));
    assertThat(records.require("configs", id).toString()).doesNotContain("super-sensitive-value");
    var node = yaml.parse(source("secret-job"));
    node.withObject("/spec")
        .withObject("/podTemplate")
        .withObject("/spec")
        .putArray("containers")
        .addObject()
        .put("name", "flink-main-container")
        .putArray("env")
        .addObject()
        .put("name", "PASSWORD")
        .put("value", "{{secret:" + id + "}}");
    var rendered = renderer.render(node);
    assertThat(rendered.deployment().toString())
        .doesNotContain("super-sensitive-value")
        .contains("secretKeyRef");
    assertThat(rendered.secrets()).hasSize(1);
    ((ObjectNode) node.get("spec")).put("image", "{{secret:" + id + "}}");
    assertThatThrownBy(() -> renderer.render(node)).isInstanceOf(BusinessException.class);
  }

  /** S3 配置引用在通用和角色模板中转换为密钥环境变量，预览不含明文。 */
  @Test
  @WithMockUser(roles = "ADMIN")
  void s3ReferencesBecomeSecretEnvironment() {
    String accessId = "s3-access-" + UUID.randomUUID(), secretId = "s3-secret-" + UUID.randomUUID();
    catalogs.save(
        "configs",
        accessId,
        mapper.createObjectNode().put("kind", "secret").put("value", "fake-s3-access"));
    catalogs.save(
        "configs",
        secretId,
        mapper
            .createObjectNode()
            .put("kind", "secretRef")
            .put("secretName", "workload-s3")
            .put("secretKey", "secret-key"));
    var source = yaml.parse(source("s3-test"));
    var config = source.withObject("/spec/flinkConfiguration");
    config
        .put("s3.access-key", "{{secret:" + accessId + "}}")
        .put("s3.secret-key", "{{secret:" + secretId + "}}")
        .put("s3.endpoint", "https://s3.example.test");
    for (String path :
        java.util.List.of(
            "/spec/podTemplate", "/spec/jobManager/podTemplate", "/spec/taskManager/podTemplate")) {
      var containers = source.withObject(path + "/spec").putArray("containers");
      containers.addObject().put("name", "sidecar").put("image", "test");
      var env = containers.addObject().put("name", "flink-main-container").putArray("env");
      env.addObject().put("name", "OTHER").put("value", "keep");
      env.addObject().put("name", "AWS_SECRET_ACCESS_KEY").put("value", "old-secret");
    }
    var rendered = renderer.render(source);
    assertThat(rendered.deployment().toString())
        .doesNotContain("fake-s3-access", "old-secret", "{{secret:")
        .contains("workload-s3", "secretKeyRef", "sidecar", "OTHER");
    assertThat(rendered.deployment().at("/spec/flinkConfiguration").has("s3.access-key")).isFalse();
    assertThat(source.at("/spec/flinkConfiguration/s3.access-key").asText()).contains("{{secret:");
    for (String path : java.util.List.of("/spec/podTemplate")) {
      var env = rendered.deployment().at(path + "/spec/containers/1/env");
      assertThat(env).hasSize(3);
      assertThat(env.get(2).at("/valueFrom/secretKeyRef/name").asText()).isEqualTo("workload-s3");
    }
    config.put("s3.secret-key", "literal-not-allowed");
    assertThatThrownBy(() -> renderer.render(source))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("必须同时引用");
  }

  /** 验证只读权限及普通草稿列表查询不会访问 Kubernetes。 */
  @Test
  @WithMockUser(roles = "VIEWER")
  void readOnlyAndNoBackgroundClusterCalls() throws Exception {
    mvc.perform(get("/api/jobs")).andExpect(status().isOk());
    mvc.perform(
            put("/api/catalog/configs/example")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/users")).andExpect(status().isForbidden());
    verifyNoInteractions(kubernetes);
  }

  /** 验证登录需要 CSRF 且初始化管理员能够认证。 */
  @Test
  void loginNeedsCsrfAndWorksWithBootstrapUser() throws Exception {
    mvc.perform(get("/api/jobs")).andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/auth/login")
                .param("username", "admin")
                .param("password", "Test-password-12345"))
        .andExpect(status().isForbidden());
    mvc.perform(encryptedLogin()).andExpect(status().isOk());
  }

  /** HTTP 明文登录拒绝，公钥接口不缓存，重放已消费密文也不能再次认证。 */
  @Test
  void transportRejectsPlaintextAndReplay() throws Exception {
    mvc.perform(get("/api/auth/transport-key").param("purpose", "login").param("target", "admin"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.data.publicKey").isNotEmpty());
    mvc.perform(
            post("/api/auth/login")
                .with(csrf())
                .param("username", "admin")
                .param("password", "Test-password-12345"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIAL_ENVELOPE"));
    var session = new org.springframework.mock.web.MockHttpSession();
    String value =
        CredentialEnvelopeFixture.encrypt(
            transport.issue(session, "login", "admin"), "Test-password-12345");
    mvc.perform(
            post("/api/auth/login")
                .session(session)
                .with(csrf())
                .param("username", "admin")
                .param("password", value))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/auth/login")
                .session(session)
                .with(csrf())
                .param("username", "admin")
                .param("password", value))
        .andExpect(status().isBadRequest());
  }

  /** 加密创建用户后仍使用 BCrypt；明文及跨用户目标密文均拒绝。 */
  @Test
  @WithMockUser(roles = "ADMIN")
  void encryptedUserPasswordUsesExistingHashFlow() throws Exception {
    var session = new org.springframework.mock.web.MockHttpSession();
    String name = "enc-" + UUID.randomUUID().toString().substring(0, 8), plain = "Browser-密码-12345";
    var input =
        mapper
            .createObjectNode()
            .put("role", "VIEWER")
            .put("revision", 0)
            .put(
                "password",
                CredentialEnvelopeFixture.encrypt(
                    transport.issue(session, "user-password", name), plain));
    mvc.perform(
            put("/api/users/" + name)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(input.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    assertThat(
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .matches(plain, records.require("users", name).path("passwordHash").asText()))
        .isTrue();
    input.put("password", plain);
    mvc.perform(
            put("/api/users/" + name)
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(input.toString()))
        .andExpect(status().isBadRequest());
    mvc.perform(
            put("/api/catalog/configs/plain-rejected")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.createObjectNode().put("kind", "secret").put("value", plain).toString()))
        .andExpect(status().isBadRequest());
  }

  /** 验证草稿乐观锁与部署幂等性，重复请求不会重复提交资源。 */
  @Test
  @WithMockUser(roles = "ADMIN")
  void draftCasAndIdempotentDeploy() throws Exception {
    String name = "job-" + UUID.randomUUID().toString().substring(0, 8);
    var draft =
        mapper
            .createObjectNode()
            .put("name", name)
            .put("description", "test")
            .put("flinkVersion", "1.20")
            .put("yaml", source(name))
            .put("revision", 0);
    var created =
        mvc.perform(
                post("/api/jobs")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(draft.toString()))
            .andExpect(status().isCreated())
            .andReturn();
    String id = mapper.readTree(created.getResponse().getContentAsString()).at("/data/id").asText();
    mvc.perform(
            put("/api/jobs/" + id + "/draft")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(draft.toString()))
        .andExpect(status().isOk());
    mvc.perform(
            put("/api/jobs/" + id + "/draft")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(draft.toString()))
        .andExpect(status().isConflict());
    when(kubernetes.base()).thenReturn("/cr");
    when(kubernetes.call(eq("GET"), anyString(), isNull())).thenReturn(MissingNode.getInstance());
    when(kubernetes.call(eq("POST"), anyString(), any()))
        .thenAnswer(
            inv -> {
              ObjectNode body = ((ObjectNode) inv.getArgument(2)).deepCopy();
              body.withObject("/metadata").put("resourceVersion", "123");
              return body;
            });
    String body =
        mapper
            .createObjectNode()
            .put("revision", 1)
            .put("requestId", UUID.randomUUID().toString())
            .toString();
    for (int i = 0; i < 2; i++)
      mvc.perform(
              post("/api/jobs/" + id + "/actions/deploy")
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.state").value("ACCEPTED"));
    var submitted = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
    verify(kubernetes, times(1)).call(eq("POST"), eq("/cr"), submitted.capture());
    assertThat(submitted.getValue().at("/metadata/name").asText())
        .isEqualTo(
            JobDraft.numberedName(
                mapper
                    .readTree(created.getResponse().getContentAsString())
                    .at("/data/jobNumber")
                    .asLong()));
    verify(kubernetes, times(1))
        .call(eq("POST"), eq("/cr?dryRun=All&fieldValidation=Strict"), any());
  }

  /** 验证表单正确组合模板、镜像和 JM/TM 制品下载配置。 */
  @Test
  void formCombinesTemplateImageAndArtifactsForBothPodRoles() {
    String suffix = UUID.randomUUID().toString(),
        imageId = "image-" + suffix,
        artifactId = "artifact-" + suffix;
    records.save(
        "images",
        imageId,
        mapper
            .createObjectNode()
            .put("repository", "registry.test/flink")
            .put("downloaderImage", "legacy/downloader:ignored")
            .put("version", "1.20")
            .put("flinkVersion", "1.20"),
        0);
    records.save(
        "artifacts",
        artifactId,
        mapper
            .createObjectNode()
            .put("uri", "s3://bucket/job.jar")
            .put("filename", "job.jar")
            .put("sha256", "a".repeat(64)),
        0);
    var input =
        mapper
            .createObjectNode()
            .put("name", "form-job")
            .put("templateId", "application-pod")
            .put("imageId", imageId);
    input.putArray("artifactIds").add(artifactId);
    var output = forms.render(input);
    var deployment = yaml.validate(yaml.parse(output.path("yaml").asText()), "form-job");
    assertThat(deployment.at("/spec/image").asText()).isEqualTo("registry.test/flink:1.20");
    assertThat(deployment.at("/spec/job/jarURI").asText())
        .isEqualTo("local:///opt/flink/usrlib/job.jar");
    assertThat(deployment.at("/spec/podTemplate/spec/initContainers/0/image").asText())
        .isEqualTo("registry.test/downloader:kylin-sp3");
    assertThat(deployment.at("/spec/podTemplate/spec/containers/0/env/0/name").asText())
        .isEqualTo("TZ");
    assertThat(deployment.at("/spec/jobManager/podTemplate").isMissingNode()).isTrue();
    assertThat(deployment.at("/spec/taskManager/podTemplate").isMissingNode()).isTrue();
    String env = deployment.at("/spec/podTemplate/spec/initContainers/0/env").toString();
    assertThat(env)
        .contains("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "{{secret:s3-access-key}}");
    assertThat(env).doesNotContain("sha256", "AWS_DEFAULT_REGION", "AWS_REGION");
  }

  /** 验证账号修订变化会使已有会话失效。 */
  @Test
  void changedAccountRevokesExistingSession() throws Exception {
    var login = mvc.perform(encryptedLogin()).andExpect(status().isOk()).andReturn();
    var session =
        (org.springframework.mock.web.MockHttpSession) login.getRequest().getSession(false);
    var admin = records.require("users", "admin");
    records.save("users", "admin", admin, admin.path("revision").asLong());
    mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());
  }

  @Autowired com.flinkweb.service.permission.SecretTransportService transport;

  /** 模拟浏览器以同一会话的一次性挑战提交加密登录，同时保留 CSRF 校验。 */
  org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder encryptedLogin()
      throws Exception {
    var session = new org.springframework.mock.web.MockHttpSession();
    var challenge = transport.issue(session, "login", "admin");
    return post("/api/auth/login")
        .session(session)
        .with(csrf())
        .param("username", "admin")
        .param("password", CredentialEnvelopeFixture.encrypt(challenge, "Test-password-12345"));
  }

  @Autowired JobAdoptionService adoption;
  @Autowired JobOwnershipService ownership;
  @Autowired JobService jobs;
  @Autowired com.flinkweb.repository.job.JobRepository jobRepository;
  @Autowired com.flinkweb.repository.job.JobBindingRepository bindings;

  /** 生成含旧 ServiceAccount 和 checkpoint 策略的既有作业样例。 */
  ObjectNode legacy(String name) {
    ObjectNode live = yaml.parse(source(name));
    live.withObject("/metadata")
        .put("namespace", "flink-jobs")
        .put("uid", "uid-" + name)
        .put("resourceVersion", "42");
    live.withObject("/metadata").withObject("/labels").put("app.kubernetes.io/managed-by", "Helm");
    live.withObject("/spec").put("serviceAccount", "legacy-flink");
    live.withObject("/spec")
        .withObject("/flinkConfiguration")
        .put("state.checkpoints.num-retained", "5");
    live.withObject("/status").withObject("/jobStatus").put("state", "RUNNING");
    return live;
  }

  /** 从集群样例提取名称、UID 和版本，模拟用户确认的纳管请求。 */
  AdoptJobRequest adoptionRequest(ObjectNode live) {
    return new AdoptJobRequest(
        live.at("/metadata/name").asText(),
        live.at("/metadata/uid").asText(),
        live.at("/metadata/resourceVersion").asText());
  }

  /** 验证发现及纳管保留原 spec、重复纳管不覆盖编辑且全程无集群写入。 */
  @Test
  @WithMockUser(roles = "ADMIN")
  void discoveryAndAdoptionPreserveExistingJobWithoutClusterWrites() throws Exception {
    String name = "legacy-" + UUID.randomUUID().toString().substring(0, 8);
    ObjectNode live = legacy(name);
    when(kubernetes.base()).thenReturn("/cr");
    when(kubernetes.call(eq("GET"), eq("/cr"), isNull()))
        .thenReturn(mapper.createObjectNode().set("items", mapper.createArrayNode().add(live)));
    when(kubernetes.call(eq("GET"), contains("flinkstatesnapshots"), isNull()))
        .thenReturn(MissingNode.getInstance());
    mvc.perform(post("/api/jobs/refresh").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].canAdopt").value(true));
    when(kubernetes.require(name)).thenReturn(live);
    String body = mapper.writeValueAsString(adoptionRequest(live));
    var response =
        mvc.perform(
                post("/api/jobs/adopt")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andReturn();
    String id =
        mapper.readTree(response.getResponse().getContentAsString()).at("/data/id").asText();
    org.springframework.security.core.context.SecurityContextHolder.setContext(
        org.springframework.security.test.context.TestSecurityContextHolder.getContext());
    ObjectNode saved = yaml.parse(jobs.get(id).yaml());
    assertThat(saved.path("spec")).isEqualTo(live.path("spec"));
    assertThat(saved.has("status")).isFalse();
    assertThat(saved.at("/metadata/uid").isMissingNode()).isTrue();
    assertThat(bindings.findByJobId(id).orElseThrow().resourceUid()).isEqualTo("uid-" + name);
    jobs.update(id, new DraftRequest(name, "edited", "1.20", jobs.get(id).yaml(), 0));
    assertThat(adoption.adopt(adoptionRequest(live)).description()).isEqualTo("edited");
    mvc.perform(post("/api/jobs/refresh").with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.items[0].managed").value(true))
        .andExpect(jsonPath("$.data.items[0].jobId").value(id));
    verify(kubernetes, never()).call(argThat(method -> !method.equals("GET")), anyString(), any());
  }

  /** 验证开发者不能调用管理员纳管接口。 */
  @Test
  @WithMockUser(roles = "DEVELOPER")
  void adoptionRequiresAdministrator() throws Exception {
    mvc.perform(
            post("/api/jobs/adopt")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"legacy\",\"uid\":\"uid-legacy\",\"resourceVersion\":\"42\"}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(kubernetes);
  }

  /** 验证资源身份、版本或类型变化时拒绝纳管且不残留草稿。 */
  @Test
  void adoptionRejectsChangedIdentityVersionNamespaceAndSessionCluster() {
    String name = "reject-" + UUID.randomUUID().toString().substring(0, 8);
    ObjectNode original = legacy(name);
    AdoptJobRequest request = adoptionRequest(original);
    for (String reason :
        java.util.List.of("uid", "resourceVersion", "namespace", "deleting", "session")) {
      ObjectNode changed = original.deepCopy();
      switch (reason) {
        case "uid" -> changed.withObject("/metadata").put("uid", "replacement");
        case "resourceVersion" -> changed.withObject("/metadata").put("resourceVersion", "43");
        case "namespace" -> changed.withObject("/metadata").put("namespace", "foreign");
        case "deleting" ->
            changed.withObject("/metadata").put("deletionTimestamp", "2026-01-01T00:00:00Z");
        case "session" -> changed.withObject("/spec").remove("job");
      }
      when(kubernetes.require(name)).thenReturn(changed);
      assertThatThrownBy(() -> adoption.adopt(request)).isInstanceOf(BusinessException.class);
    }
    assertThat(jobs.list()).noneMatch(job -> job.name().equals(name));
  }

  /** 验证纳管后可停止原作业，同名新 UID 不能被修改或直接移除登记。 */
  @Test
  @WithMockUser(roles = "ADMIN")
  void adoptedJobCanStopButReplacementUidCannotBeMutated() throws Exception {
    String name = "stop-" + UUID.randomUUID().toString().substring(0, 8);
    ObjectNode live = legacy(name);
    live.withObject("/spec/flinkConfiguration")
        .put("state.savepoints.dir", "s3://state/savepoints");
    when(kubernetes.require(name)).thenReturn(live);
    var adopted = adoption.adopt(adoptionRequest(live));
    when(kubernetes.base()).thenReturn("/cr");
    when(kubernetes.call(eq("GET"), eq("/cr/" + name), isNull())).thenReturn(live);
    when(kubernetes.call(eq("PUT"), anyString(), any()))
        .thenAnswer(inv -> ((ObjectNode) inv.getArgument(2)).deepCopy());
    var request =
        mapper.createObjectNode().put("revision", 0).put("requestId", UUID.randomUUID().toString());
    mvc.perform(
            post("/api/jobs/" + adopted.id() + "/actions/stop")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.state").value("ACCEPTED"));
    verify(kubernetes)
        .call(
            eq("PUT"),
            eq("/cr/" + name),
            argThat(
                body ->
                    body.at("/spec/job/state").asText().equals("suspended")
                        && body.at("/spec/serviceAccount").asText().equals("legacy-flink")
                        && body.at("/metadata/labels/app.kubernetes.io~1managed-by")
                            .asText()
                            .equals("Helm")));
    clearInvocations(kubernetes);
    live.withObject("/metadata").put("uid", "replacement");
    request.put("requestId", UUID.randomUUID().toString());
    mvc.perform(
            post("/api/jobs/" + adopted.id() + "/actions/start")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.errorCode").value("RESOURCE_IDENTITY_CHANGED"));
    verify(kubernetes, never()).call(argThat(method -> !method.equals("GET")), anyString(), any());
    mvc.perform(delete("/api/jobs/" + adopted.id() + "/draft?revision=0").with(csrf()))
        .andExpect(status().isConflict());
  }

  /** 验证纳管不会覆盖平台已有的同名草稿。 */
  @Test
  void adoptionDoesNotOverwriteSameNameDraft() {
    String name = "collision-" + UUID.randomUUID().toString().substring(0, 8);
    jobs.create(new DraftRequest(name, "keep", "1.20", source(name), 0));
    ObjectNode live = legacy(name);
    when(kubernetes.require(name)).thenReturn(live);
    assertThatThrownBy(() -> adoption.adopt(adoptionRequest(live)))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).code())
        .isEqualTo("JOB_NAME_CONFLICT");
  }

  /** 验证记录修订号持久化以及过期修订被拒绝。 */
  @Test
  void persistedRecordsRetainRevision() {
    String id = UUID.randomUUID().toString();
    records.save("tests", id, mapper.createObjectNode().put("value", "persisted"), 0);
    assertThat(records.require("tests", id).path("revision").asLong()).isEqualTo(1);
    assertThat(Files.exists(DATABASE)).isTrue();
    assertThatThrownBy(() -> records.save("tests", id, mapper.createObjectNode(), 7))
        .isInstanceOf(BusinessException.class);
  }

  @Autowired SqlJobService sqlJobs;

  /** 创建独立镜像登记和 SQL 运行参数，避免测试相互依赖。 */
  ObjectNode sqlRuntime(String suffix) {
    String imageId = "sql-image-" + suffix;
    records.save(
        "images",
        imageId,
        mapper
            .createObjectNode()
            .put("repository", "registry.test/flink-sql")
            .put("downloaderImage", "legacy/downloader:ignored")
            .put("version", "1.20")
            .put("flinkVersion", "1.20"),
        0);
    return mapper
        .createObjectNode()
        .put("imageId", imageId)
        .put("jarURI", "local:///opt/flink/lib/flink-web-sql-runner.jar")
        .put("parallelism", 1)
        .put("upgradeMode", "savepoint");
  }

  /** SQL 草稿保存不访问集群，发布能解码还原脚本并关联同一部署草稿。 */
  @Test
  @WithMockUser(roles = "DEVELOPER")
  void sqlDraftPublishPreservesStatementsAndUsesCas() throws Exception {
    String name = "sql-" + UUID.randomUUID().toString().substring(0, 8);
    String script =
        "CREATE TABLE output_table (n INT) WITH ('connector'='blackhole'); INSERT INTO"
            + " output_table VALUES (1);";
    var runtime = sqlRuntime(name);
    var saved = sqlJobs.save(name, new SqlDraftRequest(name, "sql test", script, runtime, 0));
    var deployed = sqlJobs.publish(name, saved.path("revision").asLong());
    ObjectNode resource = yaml.parse(deployed.yaml());
    assertThat(resource.at("/spec/job/entryClass").asText())
        .isEqualTo("com.flinkweb.sql.SqlRunner");
    assertThat(resource.at("/metadata/labels/flink-web~1job-type").asText()).isEqualTo("sql");
    assertThat(
            new String(
                java.util.Base64.getDecoder().decode(resource.at("/spec/job/args/1").asText()),
                java.nio.charset.StandardCharsets.UTF_8))
        .startsWith("CREATE TABLE output_table");
    assertThat(resource.at("/spec/job/args/3").asText()).isNotBlank();
    assertThatThrownBy(() -> sqlJobs.publish(name, 1)).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> sqlJobs.delete(name, 2)).isInstanceOf(BusinessException.class);
    jobs.update(
        deployed.id(),
        new DraftRequest(name, "edited", "1.20", deployed.yaml(), deployed.revision()));
    var republished = sqlJobs.publish(name, 2);
    assertThat(republished.id()).isEqualTo(deployed.id());
    assertThat(republished.revision()).isEqualTo(2);
    verifyNoInteractions(kubernetes);
    mvc.perform(get("/api/sql/jobs")).andExpect(status().isOk());
  }

  /** 未完成脚本可以保存继续开发，预览和发布必须通过基础检查。 */
  @Test
  @WithMockUser(roles = "ADMIN")
  void sqlIncompleteDraftCannotPublishButCanBeDeleted() {
    String name = "sql-incomplete-" + UUID.randomUUID().toString().substring(0, 8);
    sqlJobs.save(name, new SqlDraftRequest(name, "", "CREATE TABLE", mapper.createObjectNode(), 0));
    assertThatThrownBy(() -> sqlJobs.publish(name, 1)).isInstanceOf(BusinessException.class);
    assertThat(jobs.list()).noneMatch(j -> j.name().equals(name));
    sqlJobs.delete(name, 1);
    assertThatThrownBy(() -> sqlJobs.get(name)).isInstanceOf(BusinessException.class);
    verifyNoInteractions(kubernetes);
  }

  /** 只读与操作员角色不能更改 SQL 或发布部署，缺少 CSRF 也不能保存。 */
  @Test
  @WithMockUser(roles = "VIEWER")
  void sqlWriteEndpointsEnforceRolesAndCsrf() throws Exception {
    mvc.perform(get("/api/sql/jobs")).andExpect(status().isOk());
    mvc.perform(
            post("/api/sql/validate")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sql\":\"INSERT INTO t VALUES(1)\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/sql/jobs/test/publish")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":1}"))
        .andExpect(status().isForbidden());
    mvc.perform(put("/api/sql/jobs/test").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(kubernetes);
  }

  /** 分离主包与附加依赖后，入口始终指向主 JAR；仅有依赖时保留镜像内入口。 */
  @Test
  void jarFormSeparatesMainJarFromDependencies() {
    String suffix = UUID.randomUUID().toString();
    ObjectNode input =
        sqlRuntime(suffix)
            .put("name", "jar-form")
            .put("jarURI", "local:///opt/flink/lib/builtin.jar")
            .put("entryClass", "com.example.Main");
    String main = "main-" + suffix, dep = "dep-" + suffix;
    records.save(
        "artifacts",
        main,
        mapper
            .createObjectNode()
            .put("uri", "s3://bucket/main.jar")
            .put("filename", "main.jar")
            .put("sha256", "a".repeat(64)),
        0);
    records.save(
        "artifacts",
        dep,
        mapper
            .createObjectNode()
            .put("uri", "s3://bucket/connector.jar")
            .put("filename", "connector.jar")
            .put("sha256", "b".repeat(64)),
        0);
    input.put("mainArtifactId", main);
    input.putArray("dependencyArtifactIds").add(dep);
    input.putArray("args").add("--message").add("hello world");
    var generated = yaml.parse(forms.render(input).path("yaml").asText());
    assertThat(generated.at("/spec/job/jarURI").asText())
        .isEqualTo("local:///opt/flink/usrlib/main.jar");
    assertThat(generated.at("/spec/job/args/1").asText()).isEqualTo("hello world");
    input.remove("mainArtifactId");
    assertThat(
            yaml.parse(forms.render(input).path("yaml").asText()).at("/spec/job/jarURI").asText())
        .isEqualTo("local:///opt/flink/lib/builtin.jar");
    input.put("jarURI", "local:///opt/flink/usrlib/hidden.jar");
    assertThatThrownBy(() -> forms.render(input)).isInstanceOf(BusinessException.class);
    input.put("jarURI", "local:///opt/flink/lib/builtin.jar");
    input.put("parallelism", 1.5);
    assertThatThrownBy(() -> forms.render(input)).isInstanceOf(BusinessException.class);
    input.put("parallelism", 1).put("mainArtifactId", dep);
    assertThatThrownBy(() -> forms.render(input)).isInstanceOf(BusinessException.class);
  }

  /** 验证数据源沿用管理员权限、通用存储修订号和密钥引用策略。 */
  @Test
  @WithMockUser(roles = "ADMIN")
  void datasourceCatalogPersistsAndGeneratesSnippet() throws Exception {
    String id = "source-" + UUID.randomUUID();
    var body =
        mapper
            .createObjectNode()
            .put("name", "Kafka source")
            .put("connectorType", "kafka")
            .put("tableName", "events")
            .put("brokers", "kafka:9092")
            .put("topic", "orders")
            .put("groupId", "consumer")
            .put("format", "json")
            .put("revision", 0);
    body.putArray("columns").addObject().put("name", "id").put("type", "BIGINT");
    mvc.perform(
            put("/api/catalog/datasources/" + id)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.revision").value(1));
    mvc.perform(get("/api/sql/datasources/" + id + "/snippet"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.data.sql")
                .value(org.hamcrest.Matchers.containsString("'connector' = 'kafka'")));
    mvc.perform(
            put("/api/catalog/datasources/" + id)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()))
        .andExpect(status().isConflict());
    verifyNoInteractions(kubernetes);
  }

  /** 验证只读用户不能注册数据源。 */
  @Test
  @WithMockUser(roles = "VIEWER")
  void viewerCannotRegisterDatasource() throws Exception {
    mvc.perform(
            put("/api/catalog/datasources/forbidden")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  /** 表单编辑以原 YAML 为底稿，保留自定义字段并允许清空状态配置。 */
  @Test
  void formRoundtripPreservesUnmanagedFieldsAndRemovesDisabledStateOptions() {
    String imageId = "roundtrip-" + UUID.randomUUID();
    catalogs.save(
        "images",
        imageId,
        mapper
            .createObjectNode()
            .put("name", imageId)
            .put("repository", "registry.test/flink")
            .put("downloaderImage", "legacy/downloader:ignored")
            .put("version", "1.20.5"));
    var original = yaml.parse(source("roundtrip"));
    original.withObject("/spec").put("restartNonce", 123);
    original.withObject("/spec/jobManager").put("replicas", 2);
    original.withObject("/spec/flinkConfiguration").put("state.checkpoints.dir", "s3://old/cp");
    original.withObject("/spec/job").put("initialSavepointPath", "s3://state/preserved");
    var input =
        mapper
            .createObjectNode()
            .put("name", "roundtrip")
            .put("imageId", imageId)
            .put("baseYaml", yaml.dump(original))
            .put("parallelism", 3)
            .put("jmMemory", "2g");
    input.putObject("flinkConfiguration").put("custom.option", "kept");
    var rendered = yaml.parse(forms.render(input).path("yaml").asText());
    assertThat(rendered.at("/spec/restartNonce").asInt()).isEqualTo(123);
    assertThat(rendered.at("/spec/jobManager/replicas").asInt()).isEqualTo(2);
    assertThat(rendered.at("/spec/jobManager/resource/memory").asText()).isEqualTo("2g");
    assertThat(rendered.at("/spec/job/initialSavepointPath").asText())
        .isEqualTo("s3://state/preserved");
    assertThat(rendered.at("/spec/flinkConfiguration").has("state.checkpoints.dir")).isFalse();
    assertThat(
            yaml.validate(rendered, "roundtrip")
                .at("/spec/flinkConfiguration")
                .has("execution.checkpointing.interval"))
        .isFalse();
  }

  /** 旧资源确认不存在后，启动按平台编号创建并保留平台展示名称。 */
  @Test
  @WithMockUser(roles = "ADMIN")
  void legacyJobStartsWithNumberedResourceAfterDeletion() throws Exception {
    String name = "migrate-" + UUID.randomUUID().toString().substring(0, 8);
    ObjectNode old = legacy(name);
    old.withObject("/spec").remove("serviceAccount");
    when(kubernetes.require(name)).thenReturn(old);
    var job = adoption.adopt(adoptionRequest(old));
    when(kubernetes.base()).thenReturn("/cr");
    when(kubernetes.call(eq("GET"), anyString(), isNull()))
        .thenReturn(com.fasterxml.jackson.databind.node.MissingNode.getInstance());
    when(kubernetes.call(eq("POST"), anyString(), any()))
        .thenAnswer(i -> ((ObjectNode) i.getArgument(2)).deepCopy());
    mvc.perform(
            post("/api/jobs/" + job.id() + "/actions/start")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper
                        .createObjectNode()
                        .put("revision", job.revision())
                        .put("requestId", UUID.randomUUID().toString())
                        .toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.state").value("ACCEPTED"));
    assertThat(jobRepository.findById(job.id()).orElseThrow().name()).isEqualTo(name);
    assertThat(jobRepository.findById(job.id()).orElseThrow().deploymentName())
        .isEqualTo(JobDraft.numberedName(job.jobNumber()));
    verify(kubernetes)
        .call(
            eq("POST"),
            eq("/cr"),
            argThat(
                body ->
                    body.at("/metadata/name")
                            .asText()
                            .equals(JobDraft.numberedName(job.jobNumber()))
                        && body.at("/spec/job/state").asText().equals("running")));
  }

  /** last-state 必须同时配置 HA 类型与目录，避免仅配置类型通过校验。 */
  @Test
  void lastStateRequiresHaStorageDirectory() {
    var node = yaml.parse(source("ha-test"));
    node.withObject("/spec/job").put("upgradeMode", "last-state");
    node.withObject("/spec/flinkConfiguration").put("high-availability.type", "kubernetes");
    assertThatThrownBy(() -> yaml.validate(node, "ha-test"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("HA");
    node.withObject("/spec/flinkConfiguration")
        .put("high-availability.storageDir", "s3://state/ha");
    assertThat(
            yaml.validate(node, "ha-test")
                .at("/spec/flinkConfiguration/high-availability.storageDir")
                .asText())
        .isEqualTo("s3://state/ha");
  }

  /** 下载器为独立单例设置，拒绝旧修订覆盖且不改变 Flink 镜像记录。 */
  @Test
  @WithMockUser(roles = "ADMIN")
  void downloaderBindingIsSingletonAndRevisionProtected() throws Exception {
    try {
      mvc.perform(
              put("/api/system/downloader")
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"image\":\"registry.test/downloader:new\",\"revision\":0}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.revision").value(1));
      mvc.perform(
              put("/api/system/downloader")
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"image\":\"registry.test/downloader:stale\",\"revision\":0}"))
          .andExpect(status().isConflict());
      mvc.perform(get("/api/system/downloader"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.image").value("registry.test/downloader:new"));
    } finally {
      var saved = records.find("settings", "downloader");
      if (saved != null) records.delete("settings", "downloader", saved.path("revision").asLong());
    }
  }

  /** 普通开发者可以读取统一配置，但不能修改下载器绑定。 */
  @Test
  @WithMockUser(roles = "DEVELOPER")
  void downloaderBindingRequiresAdministrator() throws Exception {
    mvc.perform(get("/api/system/downloader")).andExpect(status().isOk());
    mvc.perform(
            put("/api/system/downloader")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"image\":\"registry.test/downloader:new\",\"revision\":0}"))
        .andExpect(status().isForbidden());
  }

  /** 历史双角色下载器重渲染统一镜像并注入 S3 Secret，去除校验和及区域变量。 */
  @Test
  void legacyDownloadersNormalizeAndResolveCredentialsWithoutStateStorage() {
    for (String id : java.util.List.of("s3-access-key", "s3-secret-key")) {
      var old = records.find("configs", id);
      records.save(
          "configs",
          id,
          mapper
              .createObjectNode()
              .put("kind", "secretRef")
              .put("secretName", "download-s3")
              .put("secretKey", id),
          old == null ? 0 : old.path("revision").asLong());
    }
    var source = yaml.parse(source("legacy-download"));
    String manifest =
        "[{\"uri\":\"s3://bucket/job.jar\",\"filename\":\"job.jar\",\"sha256\":\"old\"}]";
    for (String role : java.util.List.of("jobManager", "taskManager")) {
      var init =
          source
              .withObject("/spec/" + role + "/podTemplate/spec")
              .putArray("initContainers")
              .addObject()
              .put("name", "fw-download")
              .put("image", "old-" + role + ":tag");
      var env = init.putArray("env");
      env.addObject().put("name", "ARTIFACT_MANIFEST").put("value", manifest);
      env.addObject().put("name", "AWS_DEFAULT_REGION").put("value", "us-east-1");
      env.addObject().put("name", "AWS_SECRET_ACCESS_KEY").put("value", "obsolete-literal");
    }
    var result = renderer.render(source).deployment();
    assertThat(result.at("/spec/podTemplate/spec/initContainers/0/image").asText())
        .isEqualTo("registry.test/downloader:kylin-sp3");
    assertThat(result.at("/spec/jobManager/podTemplate").isMissingNode()).isTrue();
    assertThat(result.at("/spec/taskManager/podTemplate").isMissingNode()).isTrue();
    assertThat(result.at("/spec/flinkConfiguration").isMissingNode()).isTrue();
    assertThat(result.toString())
        .contains("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "download-s3", "secretKeyRef")
        .doesNotContain("sha256", "AWS_DEFAULT_REGION", "obsolete-literal", "{{secret:");
    assertThat(source.toString()).contains("old-jobManager", "sha256");
  }

  /** 不同角色的自定义配置不能被静默删除或扩散到另一角色。 */
  @Test
  void incompatibleRoleTemplatesAreRejectedWithoutMutatingSource() {
    var source = yaml.parse(source("custom-pods"));
    source
        .withObject("/spec/jobManager/podTemplate/spec")
        .putObject("nodeSelector")
        .put("role", "jm");
    var copy = source.deepCopy();
    assertThatThrownBy(() -> renderer.render(source))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("无法无损合并");
    assertThat(source).isEqualTo(copy);
  }

  /** 已有或纳管资源始终仅限 YAML，新表单来源标签由后端保存且复制时继承。 */
  @Test
  @WithMockUser(roles = "ADMIN")
  void editorOriginCannotBeUpgradedByEditingYaml() throws Exception {
    String name = "origin-" + UUID.randomUUID().toString().substring(0, 8);
    var draft = jobs.create(new DraftRequest(name, "", "1.20.5", source(name), 0));
    var changed = yaml.parse(draft.yaml());
    com.flinkweb.service.job.creation.support.JobEditorPolicy.mark(changed, true);
    var saved =
        jobs.update(draft.id(), new DraftRequest(name, "", "1.20.5", yaml.dump(changed), 0));
    assertThat(
            com.flinkweb.service.job.creation.support.JobEditorPolicy.allowsForm(
                yaml.parse(saved.yaml())))
        .isFalse();
    var copied =
        jobs.copy(
            saved.id(),
            new com.flinkweb.dto.job.creation.CopyJobRequest(name + "-copy", saved.revision()));
    assertThat(
            com.flinkweb.service.job.creation.support.JobEditorPolicy.allowsForm(
                yaml.parse(copied.yaml())))
        .isFalse();
    String imageId = "origin-image-" + UUID.randomUUID();
    catalogs.save(
        "images",
        imageId,
        mapper
            .createObjectNode()
            .put("name", imageId)
            .put("repository", "flink")
            .put("version", "1.20.5"));
    var form =
        forms.render(mapper.createObjectNode().put("name", name + "-form").put("imageId", imageId));
    assertThat(
            com.flinkweb.service.job.creation.support.JobEditorPolicy.allowsForm(
                yaml.parse(form.path("yaml").asText())))
        .isTrue();
    mvc.perform(
            post("/api/jobs/render-form")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper
                        .createObjectNode()
                        .put("jobId", draft.id())
                        .put("baseYaml", yaml.dump(changed))
                        .toString()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("YAML_ONLY_JOB"));
  }
}
