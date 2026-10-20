# 作业配置与表单

更新：2026-10-20。关联 [需求](../requirements/REQ-002-authoring.md)、[决策](../decisions/ADR-004-templates.md)；来源：当前代码与 [功能说明](../../features.md)。

职责：JAR 开发映射、表单/YAML 双向编辑、模板/镜像、程序入口与资源配置。数据流：JobEditorView → jarForm/hydrateJar → render-form/FormServiceImpl → YamlServiceImpl.validate；最终发布另走生命周期/ReleaseService。

当前三处字段：podTemplate→spec.podTemplate，jmPodTemplate→spec.jobManager.podTemplate，tmPodTemplate→spec.taskManager.podTemplate。readPodTemplates/parsePodTemplate 只接受对象，三处独立保留、默认折叠；清空仅移除对应模板。PodTemplates 检查托管下载器/插件/环境与角色覆盖的冲突，必要时启用数组具名合并，显式关闭报错。后端不再把角色模板强制合成共享模板。

ServiceAccountField 按 namespace 默认填 `{namespace}-flink`；已有账号回填，后端仅检查格式，不固定替换，不给既有无账号 YAML 补值。账号和 RBAC 必须在目标 namespace 预先准备。

JAR 表单写 editor=jar-v1；既有/纳管 YAML 转换须明确 allowFormRewrite，无法表达原配置时阻止而非覆盖。JAR 主类静态读取不执行用户代码；附加文件保留用户显式选择。无模板新建 JM 2GB/Metaspace512MB，已有值不覆盖；Metaspace 在 Flink 配置中修改。

相关页面还有 SqlStudioView/SessionsView，均复用共享模板组件。已知边界：表单不证明 Pod 可调度、账号权限或所有 Operator 版本能合并成功。

2026-10-13：SQL/Session 表单与 YAML 共用部署渲染，后端从 YAML 重新回填公共资源字段；`baseYaml` 作为表单合并基底保留未知配置。新增模式/固定副本字段只在请求显式提供时写入；未配置的既有 JAR/Session 保持原规则。转换前后均校验资源身份及所选 Runner/托管下载器，语法错误保留原文。前端 [deploymentYaml.ts](../../../frontend/src/shared/utils/deploymentYaml.ts) 仅做原文结构检查，不替代后端安全校验。

YAML 省略的 JM/TM 资源字段保持省略；configuration 的可选 `formIssue` 指出缺少显式账号/资源或无法表示的内存单位。页面在回填前拦截并保留原文，YAML 保存不因此被禁用，避免表单默认值改变已有配置。

2026-10-20：`/jar`改为左侧草稿列表、右侧嵌入JobEditorView，子路由`new`与`:id/edit`按路径重建编辑器，旧`/jobs`编辑入口保留。列表可搜索/折叠/主动刷新，窄屏列表在上，编辑区按实际容器宽度切换单列；保存发布规则不变。真实修改离开需确认；初始加载可切换，卸载后的迟到请求不回填或打开旧草稿。共享FlinkConfigEditor仅用户文档编辑发出model更新，程序回填不改变业务快照。代码及回归：[JAR工作台](../../../frontend/src/modules/job-creation/pages/JarDevelopmentView.vue)、[页面用例](../../../frontend/tests/jar-workspace.spec.ts)。

## 定位代码与验证

- [podTemplates.ts](../../../frontend/src/shared/utils/podTemplates.ts)
- [PodTemplateFields.vue](../../../frontend/src/shared/components/editor/PodTemplateFields.vue)
- [ServiceAccountField.vue](../../../frontend/src/shared/components/form/ServiceAccountField.vue)
- [jarForm.ts](../../../frontend/src/modules/job-creation/jarForm.ts)
- [FormServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/job/creation/impl/FormServiceImpl.java)
- [RuntimeYamlMapper.java](../../../backend/src/main/java/com/flinkweb/service/job/creation/support/RuntimeYamlMapper.java)
- [YamlServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/job/creation/impl/YamlServiceImpl.java)
- [PodTemplates.java](../../../backend/src/main/java/com/flinkweb/service/job/creation/support/PodTemplates.java)
- [S3PluginPodTest.java](../../../backend/src/test/java/com/flinkweb/S3PluginPodTest.java)
- [runtime-templates.spec.ts](../../../frontend/tests/runtime-templates.spec.ts)

2026-10-12：停止后重建时，有编辑权限的操作者使用已保存草稿显式 ServiceAccount；未保存编辑不生效，仅运维权限仍采用运行快照账号。保存并部署按最新草稿提交。状态存储默认根读取已修复，详细规则见存储模块。

补充适用范围：ServiceAccount 例外同时覆盖旧资源仍存在的直接启动/恢复和资源删除后的重建；平台编辑角色与作业编辑权限同时满足才采用草稿账号。最终176项后端回归和实际镜像闭环通过。

2026-10-13 来源补核：旧作业 editor 标签仅标记编辑来源，确认转换及解析能力才决定能否进表单；同日后续允许确认转换替代 10-04 早期“仅 YAML”的限制。状态配置与单位输入定位 [StateStorageForm](../../../frontend/src/shared/components/form/StateStorageForm.vue)、[DurationInput](../../../frontend/src/shared/components/form/DurationInput.vue)，其值与 Flink 配置编辑器同步。文件区限高/搜索和复制后编辑方式定位 ArtifactPicker/JobsView；历史要求与替代关系见 REQ-002，未重新做本轮页面验收。
