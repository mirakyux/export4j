# 验收导航

按要核对的能力选择记录，再检查其中的版本、样例、工具、实际动作和未覆盖边界。[版本与当前状态](../current-status.md)区分已发布候选与开发能力；本目录中的历史数字不代表当前源码已重新验收。

## 导出、模板与字体

| 核对内容 | 记录 |
| --- | --- |
| 统一 PDF／DOCX 入口、主题、DTO／record 绑定及已知限制 | [统一入口验收](easy-export.md) |
| 每块内容及配置继承、Java／XML 等价 | [源内容块](content-blocks.md) |
| 混合内容、格内公式、PDF／DOCX 表示及真实文献 | [富内容编译](rich-content.md) |
| TeX／Markdown 识别、矢量与原生数学 | [公式模块](math.md) · [行内矢量](inline-vectors.md) · [学术模板](manuweave-templates.md) |
| 缓存容量、独占租约和空闲释放 | [字体缓存](font-cache.md) |
| 原生 OpenType 与静态 CFF | [原生字体](native-opentype.md) |
| 文件、资源、字体、页面与转换短入口 | [API 易用性](api-ergonomics.md) |
| 普通模型的逐页输出 | [逐页导出](paged-document-export.md) |
| 正文分栏和语义栅格 | [正文分栏](semantic-body-columns.md) · [可编辑栅格](editable-grid-layout.md) |
| 表格内容完整性与输出质量 | [真实文献集成](../literature-pdf-integration.md) · [表单可行性探针](adr-form-probe.md) |

## 设计器

| 核对内容 | 记录 |
| --- | --- |
| 业务样例、编辑控件、连续画布与表格样式 | [阶段 108](phase-108-designer-and-business-scenarios.md) · [阶段 109](phase-109-designer-editing-controls.md) · [阶段 110](phase-110-designer-canvas-and-interactions.md) · [阶段 111](phase-111-designer-table-styles.md) |
| 栅格、数组／变量与容器内部编辑 | [栅格交互](designer-grid-ux.md) · [变量绑定](designer-bindings-ux.md) · [容器编辑](designer-container-ux.md) |
| 编辑流程与嵌套内容 | [流程补齐](designer-workflow-closure.md) · [表格、容器与分栏](designer-workflow-v2.md) |
| 模板属性与多分支条件 | [属性面板](designer-template-inspector.md) · [多分支条件](designer-multi-condition.md) |

## 发布、兼容与历史阶段

| 核对内容 | 记录 |
| --- | --- |
| 已发布 RC3 的构建、Central 制品与远端消费 | [RC3 说明](../releases/0.1.0-rc.3.md) · [发布记录](../releasing.md#已发布候选记录) |
| 平台与签名分发方法、API 兼容门禁 | [阶段 105](phase-105-publication-and-compatibility.md) · [兼容规则](../api-compatibility.md) |
| 固定转换语料、拒绝输入与依赖公告 | [阶段 106](phase-106-security-and-compatibility-corpus.md) |
| Windows 应用中的打开、编辑、保存与回读 | [阶段 107](phase-107-windows-reader-compatibility.md) |
| 字体、流式、图形及其他历史能力的细分证据 | [完整记录目录](.)；文档站自动按专题和阶段编号生成列表 |

专题记录和阶段记录互补：专题页汇总同一能力的后续修正；阶段文件保留当批证据。不要以编号最大、文件最近修改或某份样例通过，推断全部格式和平台已完成验收。

## 证据边界

- 构建、单元测试、结构检查、独立渲染、视觉复查、阅读器操作、签名和远端消费分别登记。
- “已采纳”的 [ADR](../adr/README.md)说明设计基线；当前支持状态由[能力矩阵](../capabilities-api.md)和[缺口表](../capability-gaps.md)说明。
- 共享记录只保存可复现命令、项目相对路径和结果边界。临时制品、原始日志、私有样本及凭据留在忽略目录或仓库之外。
- 新增记录需从对应 API 指南链接，并在本页补充主题入口。完整目录由文档站构建生成，避免再手工维护一份阶段总表。
