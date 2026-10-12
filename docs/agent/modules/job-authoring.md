# 作业配置与表单

更新：2026-10-12。关联 [需求](../requirements/REQ-002-authoring.md)、[决策](../decisions/ADR-004-templates.md)；来源：当前代码与 [功能说明](../../features.md)。

职责：JAR 开发映射、表单/YAML 双向编辑、模板/镜像、程序入口与资源配置。数据流：JobEditorView → jarForm/hydrateJar → render-form/FormServiceImpl → YamlServiceImpl.validate；最终发布另走生命周期/ReleaseService。

当前三处字段：podTemplate→spec.podTemplate，jmPodTemplate→spec.jobManager.podTemplate，tmPodTemplate→spec.taskManager.podTemplate。readPodTemplates/parsePodTemplate 只接受对象，三处独立保留、默认折叠；清空仅移除对应模板。PodTemplates 检查托管下载器/插件/环境与角色覆盖的冲突，必要时启用数组具名合并，显式关闭报错。后端不再把角色模板强制合成共享模板。

ServiceAccountField 按 namespace 默认填 `{namespace}-flink`；已有账号回填，后端仅检查格式，不固定替换，不给既有无账号 YAML 补值。账号和 RBAC 必须在目标 namespace 预先准备。

JAR 表单写 editor=jar-v1；既有/纳管 YAML 转换须明确 allowFormRewrite，无法表达原配置时阻止而非覆盖。JAR 主类静态读取不执行用户代码；附加文件保留用户显式选择。无模板新建 JM 2GB/Metaspace512MB，已有值不覆盖；Metaspace 在 Flink 配置中修改。

相关页面还有 SqlStudioView/SessionsView，均复用共享模板组件。已知边界：表单不证明 Pod 可调度、账号权限或所有 Operator 版本能合并成功。

## 定位代码与验证

- [podTemplates.ts](../../../frontend/src/shared/utils/podTemplates.ts)
- [PodTemplateFields.vue](../../../frontend/src/shared/components/editor/PodTemplateFields.vue)
- [ServiceAccountField.vue](../../../frontend/src/shared/components/form/ServiceAccountField.vue)
- [jarForm.ts](../../../frontend/src/modules/job-creation/jarForm.ts)
- [FormServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/job/creation/impl/FormServiceImpl.java)
- [YamlServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/job/creation/impl/YamlServiceImpl.java)
- [PodTemplates.java](../../../backend/src/main/java/com/flinkweb/service/job/creation/support/PodTemplates.java)
- [S3PluginPodTest.java](../../../backend/src/test/java/com/flinkweb/S3PluginPodTest.java)
- [runtime-templates.spec.ts](../../../frontend/tests/runtime-templates.spec.ts)
