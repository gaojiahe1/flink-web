# 全站 UI 设计系统

2026-10-30：代码字体回退栈为SFMono-Regular、Consolas、Liberation Mono、Menlo、Monaco、monospace，移除代码栈中的比例字体MiSans；UI字体保持MiSans。缺少本地代码字体时ASCII应等宽，中文由通用等宽字体系统回退。测试夹具按方法/路径显式声明API，SQL PUT纳入写入记账，未知调用返回501并在测试结束时失败，避免零写入假阳性。

2026-10-29 Harness独立工作台：216px可收起会话栏、对话优先，正文13px/辅助12px/说明11px；计划先展示，过程使用原生details默认折叠，结果与审阅批准留在正文。沿用语义主题和MiSans，不采用对话表格；代码差异仍可按需展开。见 [Harness模块](agent-harness.md)。

更新：2026-10-29。关联 [REQ-006](../requirements/REQ-006-ui-system.md)。

2026-10-29：暖白/墨蓝使用白色工作面、浅蓝灰底、深墨蓝正文与更明确边框，替代原灰黄叠色；专项验证正文对比大于10、辅助/强调色大于4.5。SQL右侧当前为56px轨道、12px圆角独立竖签、8px间距及柔和选中填充，替代同日先前逐字母英文和被用户否定的硬分块图标栏；AI/UDF整词横排、Catalog整词侧排，标签不收缩，纵向独立滚动，手机横排圆角样式；覆盖1440×900、1280×560、900×650、390×844和全屏。审计表格增目标名称/编号、格式化时间/历史来源提示及按需详情；角色共享文案同步用户组/作业归属与页脚，不删除旧组织信息。新增[专项回归](../../../frontend/tests/sql-sharing-audit.spec.ts)；最终镜像6张实际页面截图保留可视化目录，隔离环境不冒充现场集群。

采用原生语义HTML、Vue3、已有Lucide图标和CodeMirror；不新增组件库或更改业务API。默认「雾灰 / 青绿」，另有「暖白 / 墨蓝」「深色开发工作台」；布局一致，通过语义色板覆盖主导航、工作面、表单、状态和代码区。作业统计改为紧凑横排，收紧辅助说明。

## 主题入口

- [启动脚本](../../../frontend/public/theme-init.js)在样式加载前读取`flink-ui-theme`，限定`mist/pearl/dark`，无效值/读取失败回退mist。由[状态](../../../frontend/src/shared/composables/useTheme.ts)统一设置根节点data-theme、color-scheme与浏览器主题色，监听storage同步其他标签页；保存失败不阻断使用。
- [选择器](../../../frontend/src/shared/components/layout/ThemePicker.vue)复用于顶栏与登录页，原生select保留键盘交互；窄屏收紧为图标入口。主题偏好属于本浏览器，不含业务或用户凭据。
- [编辑器外观](../../../frontend/src/shared/utils/editorTheme.ts)共用于SQL/YAML、Flink配置、日志与MergeView；语义CSS变量实时更新，Compartment只重配明暗模式，不重建编辑器。组件销毁或对比重建时停止主题监听。

## 字体与历史比较

- 2026-10-28用户确认MiSans。`--font-family-ui`使用本地MiSans VF，`--font-family-code`独立保留Consolas等宽栈。由[入口](../../../frontend/index.html)加载[本地字体声明](../../../frontend/public/fonts/misans/font-face.css)，只引用本站WOFF2；使用官方已经提供的56份原版语言资源，总计4,523,768字节，未转换或再次裁剪。源地址/逐文件SHA-256见[sources](../../../frontend/public/fonts/misans/sources.json)，许可与版权见[NOTICE](../../../frontend/public/fonts/misans/NOTICE.txt)和[官方协议副本](../../../frontend/public/fonts/misans/LICENSE.pdf)。平台/登录注明MiSans；资源失败时保留系统回退。
- 统一页标题18、区域16、分组14、正文13、说明12、注释11；辅助说明小于所属标题。StateStorageForm策略标题13/说明12，卡片标题14；AI格式化内容、代码、日志和各页共享变量，代码字体与界面字体分开。
- [CodeDiff](../../../frontend/src/shared/components/editor/CodeDiff.vue)消除标题网格列间隙，按实际合并视图滚动条宽度预留标题占位；ResizeObserver随窗口/内容更新并销毁。代码包装列通过flex伸展，短内容滚动条底边齐平，长内容保持MergeView共同垂直滚动，不使用会裁断长文档的固定行高。

## 设计变量与入口

| 范围 | 当前规则 | 代码 |
| --- | --- | --- |
| 字体 | MiSans；页标题18、区域16、分组14、正文13、辅助12、注释11px；代码自有等宽字号 | [tokens](../../../frontend/src/shared/styles/tokens.css)、[base](../../../frontend/src/shared/styles/base.css) |
| 外框 | 桌面导航184、顶栏48、内容横向20px；中等/窄屏沿用响应式导航、折叠68px | [MainLayout](../../../frontend/src/app/layouts/MainLayout.vue)、[layout](../../../frontend/src/app/styles/layout.css) |
| 页面与卡片 | 主宽度跟随外框；页头标题/说明同基线，窄屏自然换行；卡片16、工具栏10×14、常规间距16px | [PageHeader](../../../frontend/src/shared/components/layout/PageHeader.vue)、[components](../../../frontend/src/shared/styles/components.css) |
| 表单与列表 | 控件30px、双行记录基准56px、表头34px；字段gap10×16，长内容与多行编辑不强裁 | 同上 |
| 工作台 | SQL/JAR共享工作区高度变量，窄草稿树、可调辅助栏；Catalog布局随屏幕重排 | [studio](../../../frontend/src/modules/sql/styles/studio.css)、[JAR](../../../frontend/src/modules/job-creation/pages/JarDevelopmentView.vue)、[Catalog](../../../frontend/src/modules/flink-catalog/pages/FlinkCatalogsView.vue) |

`MainLayout` 的data-page是布局/回归定位属性，不能用于权限判断。应用页脚使用app-footer隔离样式，避免全局footer规则影响AI和诊断抽屉。后续新增页面优先使用设计变量、PageHeader、panel/form-panel和已有控件，不额外叠加一套固定宽度与大字号覆盖。

## 页面覆盖与专用规则

- 作业/Session/模板/镜像/配置/用户/审计：共享列表工具栏、表格、搜索与分页密度；用户组表格置于横向滚动容器。资源编辑表单按两列组织，模板代码区独立高度。
- SQL/JAR：减少标题和嵌套卡片间距，扩大正文；1001–1200px时SQL草稿栏适度缩窄，调整辅助栏边界时优先给SQL正文保留320px，辅助栏必要时可小于300px且ARIA边界同步。全屏、拖动、短屏保存栏保持。
- Catalog：目录列表/详情层次，窄屏页签单行横向滚动，不把“库表与函数”等标题拆成多行；配置弹窗沿用完整表单与确认。
- 文件与制品：标题与导航一致；文件路径/操作区及详情使用统一间距，文件名与目录仍保留完整操作。文件选择器按钮不再残留32px独立下限。
- 设置：网络诊断独占一行；下载器和模型入口并排，Operator与环境信息独立分组；窄屏单列。模型服务缩小概览，列表/连接表单分栏，密码与HTTP提示继续可见。
- 登录：收紧宣传区字号和登录框宽度，沿用密码显示按钮、加密登录流程与窄屏单列。
- 弹窗：命名、诊断、AI、总结、证据与比较的内边距/标题收紧；AI实际视口高度和输入区等待状态、Pod日志底部滑条不变。

同时修正删除当前SQL草稿后仍保留已删除内容并弹出新建窗口的问题：确认删除成功后通过既有open初始化空编辑状态，清除关联和地址；删除其他草稿保留当前未保存内容，取消/失败不清空。

## 回归入口

2026-11-04：Playwright拆分fixture/live，默认命令只运行无真实账号依赖的fixture，并自动管理页面服务器；live显式使用隔离后端。更新旧导航/表单、环境前提及断言，保持现行Catalog/连接/保留API契约；前端文本固定LF，使Windows格式检查可重复。192项fixture和最终镜像26项live通过，详见[回归指南](../../testing.md)。

[全站尺寸](../../../frontend/tests/ui-design.spec.ts)、[一致性](../../../frontend/tests/platform-consistency.spec.ts)共用[隔离数据](../../../frontend/tests/fixtures/platform.ts)。覆盖1440×900、1280×720、900×650、390×844，以及资源编辑和Session保存入口。现有AI、SQL/JAR、Catalog、文件、Pod与权限回归继续保留；与现场/协议替身的区别见 [验收记录](../validation.md)。

[主题回归](../../../frontend/tests/themes.spec.ts)覆盖三主题全站桌面/窄屏、登录、深色工作面、跨标签页和刷新持久化、受限存储、未保存SQL/撤销及嵌套弹窗/代码差异。`E2E_THEME=dark`可让既有AI/日志等用例在深色初始状态运行；仅用于隔离测试。

[排版/历史回归](../../../frontend/tests/typography-history.spec.ts)覆盖三主题原生滚动条及1440/1280/900/390宽度、长文档末尾、短文档等高与列边界，CDP验证实际MiSans字形、说明层级与等宽代码分离，字体加载失败仍可操作。
