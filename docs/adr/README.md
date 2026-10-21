# 架构决策记录

架构决策记录（Architecture Decision Record，ADR）保存关键取舍的背景、结论和影响，不代替使用文档。

## 记录范围

以下变化需要 ADR：

- 核心分层、模块依赖或数据流变化。
- 公共 API、SPI、单位或资源所有权契约变化。
- 引入重要基础依赖、原生组件或新的格式后端。
- 字体与塑形方案、确定性策略或发布兼容性政策调整。

局部修复和纯格式调整通常只需在变更说明中解释。

## 编号与状态

文件采用连续四位编号和简短英文名称，例如 `0001-layered-architecture.md`。

状态使用“提议”“已采纳”“已废弃”或“已取代”。“已采纳”表示设计基线，不表示功能已经实现。

已采纳记录保留历史背景。新决策改变旧结论时，新增记录并建立双向链接，标记旧记录的替代状态。

## 内容要求

每条记录至少包含标题、日期、状态、背景、决策、考虑的替代方案、影响及验证方式。未验证的依赖或性能结论必须明确说明。

## 决策索引

| 编号 | 决策 | 状态 |
| --- | --- | --- |
| 0001 | [分层架构与格式边界](0001-layered-architecture.md) | 已采纳，来自立项基线 |
| 0002 | [首个 PDF 输出闭环](0002-initial-pdf-output.md) | 已采纳，阶段一实现 |
| 0003 | [字体与基础文本排版](0003-font-text-layout.md) | 已采纳，阶段二实现 |
| 0004 | [语义段落与基础分页](0004-paragraph-pagination.md) | 已采纳，阶段三首批实现 |
| 0005 | [混合内容与基础表格](0005-basic-tables.md) | 已采纳，阶段三表格实现 |
| 0006 | [组件与 PDF 交互](0006-components-and-pdf-interactions.md) | 已采纳，PDF 首版实现 |
| 0007 | [进阶布局与字体组缺字策略](0007-advanced-layout-and-font-policy.md) | 已采纳，进阶 PDF 首版实现 |
| 0008 | [排版扩展、输出适配与本地交付](0008-production-capabilities.md) | 已采纳，首批本地实现与验证完成 |
| 0009 | [公共复杂文本、模板与受控导出](0009-shared-text-templates-and-controlled-export.md) | 已采纳，扩展默认文本路径与执行范围 |
| 0010 | [行内图像、共享行布局与 DOCX 媒体](0010-inline-image-layout-and-docx-media.md) | 已采纳 |
| 0011 | [多矩形浮动区域与固定行带](0011-multiple-rectangular-floats.md) | 已采纳 |
| 0012 | [连续文字区域与当前页分栏](0012-continuous-text-regions.md) | 已采纳 |
| 0013 | [混合组件和表格的连续区域流](0013-mixed-content-regions.md) | 已采纳 |
| 0014 | [行内语义字段与注释锚点](0014-inline-semantic-fields.md) | 已采纳 |
| 0015 | [语义表格字段与逐页解析](0015-semantic-table-fields.md) | 已采纳 |
| 0016 | [DOCX 原生字段与显式导入宽度](0016-native-docx-fields.md) | 已采纳 |
| 0017 | [常用编写入口与布局阶段两端对齐](0017-authoring-and-justification.md) | 已采纳 |
| 0018 | [统一公共链式构建与声明式表格](0018-fluent-authoring.md) | 已采纳 |
| 0019 | [嵌套文字与合并格显式续页](0019-grid-cell-continuation.md) | 已采纳，默认整组行为保持 |
| 0020 | [静态表单与行内矢量布局](0020-static-form-components.md) | 已采纳 |
| 0021 | [组件中的语义表格插槽](0021-component-semantic-insertions.md) | 已采纳 |
| 0022 | [语义表格区域续排与呈现身份](0022-semantic-table-region-flow.md) | 已采纳 |
| 0023 | [组件默认样式与显式声明](0023-component-style-defaults.md) | 已采纳，完整文字样式和颜色独立继承，保留显式意图 |
| 0024 | [合并网格中的语义表格续排](0024-grid-semantic-cell-flow.md) | 已采纳 |
| 0025 | [离线声明式模板](0025-safe-declarative-templates.md) | 已采纳 |
| 0026 | [原文簇、段落方向与塑形上下文](0026-source-clusters-and-shaping-context.md) | 已采纳 |
| 0027 | [有界静态 PDF 页面操作适配器](0027-pdf-operations-adapter.md) | 已采纳 |
| 0028 | [严格 Markdown 模板](0028-markdown-templates.md) | 已采纳 |
| 0029 | [XML 原生字段、编号与被动外链](0029-xml-fields-and-links.md) | 已采纳 |
| 0030 | [组件文字的逐属性继承](0030-property-text-inheritance.md) | 已采纳，保留旧完整样式语义 |
| 0031 | [单向长表与同步逐页输出](0031-single-pass-table-output.md) | 已采纳，固定列普通文字子集；PDFBox 页树仍增长 |
| 0032 | [语义表格列合并与标准 DOCX 网格跨度](0032-semantic-column-spans.md) | 已采纳；行合并、递归格及 Auto 跨列仍独立 |
| 0033 | [语义跨行合并与标准 vMerge](0033-semantic-row-spans.md) | 已采纳；连通行组原子分页，纵向拆组及递归格独立 |
| 0034 | [有界块格与原子内表](0034-atomic-block-cells.md) | 已采纳；两层表深、共享全树预算、原子父组及标准尾段 |
| 0035 | [XML 有界块级格内容](0035-xml-block-cells.md) | 已采纳；直接段／内表顺序、全分支检查及复制引用 |
| 0048 | [XML 固定页眉页脚与独立主题](0048-xml-page-regions.md) | 已采纳；模型声明、共享绑定和最终页区组合 |
| 0049 | [语义页区、XML 分节与 DOCX 映射](0049-semantic-page-regions-and-sections.md) | 已采纳；有序图文／表格、共享绑定、固定几何与标准页区关系 |
| 0050 | [语义 HTML／Markdown 导出与动作策略](0050-semantic-web-exports.md) | 已采纳；模型直出、逐功能损失、共享预算及原子输出 |
