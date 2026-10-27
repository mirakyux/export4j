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
| 0051 | [不可变语义编辑与可查询损失报告](0051-semantic-editing-and-loss-reports.md) | 已采纳；跨运行替换、范围回滚、预检候选与完整性校验 |
| 0052 | [离线 CommonMark／GFM 语义导入](0052-offline-commonmark-import.md) | 已采纳；成熟语法解析、显式资源、源位置及实际损失 |
| 0053 | [离线 HTML／CSS 语义子集导入](0053-offline-html-css-import.md) | 已采纳；有限级联、物理格、显式资源及损失报告 |
| 0054 | [统一转换与确切输出预检](0054-prepared-document-conversion.md) | 已采纳；单次编码、共享实际损失、未编辑内容检查与原子发布 |
| 0055 | [保留源包的 DOCX 文字编辑](0055-preserving-docx-package-edits.md) | 已采纳；普通运行编辑、复杂边界保留、整包验证与确切输出 |
| 0056 | [DOCX 样式图展开与显式字体映射](0056-materialized-docx-styles.md) | 已采纳；有限级联、真实角色、图损失及累计工作预算 |
| 0057 | [已有 PDF 的静态页级编辑](0057-static-pdf-page-edits.md) | 已采纳；物理坐标、显式叠加、裁剪损失及确切输出 |
| 0058 | [已有 PDF 的被动交互对象编辑](0058-passive-pdf-object-edits.md) | 已采纳；字段外观、对象关系、显式扁平化及原内容保持 |
| 0059 | [被动 PDF 对象的选页和合并](0059-passive-pdf-composition.md) | 已采纳；显式命名空间、关系重映射、实际损失及禁止隐式修复 |
| 0060 | [已有 PDF 的有界页面渲染与图片提取](0060-bounded-pdf-raster-and-images.md) | 已采纳；物理像素、显式字体、去重资源、实际损失及确切图像输出 |
| 0061 | [实测 PDF 优化](0061-measured-pdf-optimization.md) | 已采纳；流压缩、简单资源去重及显式有损重采样 |
| 0062 | [有界 PDF 文字替换](0062-bounded-pdf-text-replacement.md) | 已采纳；命中范围编码替换、显式字体及后续坐标保持 |
| 0063 | [新建图片 PDF 的区域脱敏](0063-fresh-raster-pdf-redaction.md) | 已采纳；清除敏感像素、不复制源对象及全文光栅化损失 |
| 0064 | [有界 PDF 语义恢复](0064-bounded-pdf-semantic-recovery.md) | 已采纳；逐行／标题推断、显式表格网格、流图片及共享转换 |
| 0065 | [递归语义格续排](0065-recursive-semantic-continuation.md) | 已采纳；有界深表、纵向 owner、事务取片及原始格块序号 |
| 0066 | [递归片段与动态目的地](0066-recursive-includes-and-dynamic-destinations.md) | 已采纳；组合命名、词法引用、完整动态目标及最终地址校验 |
| 0067 | [按实际栏宽换行](0067-width-aware-continuous-columns.md) | 已采纳；可选区域宽度、完整来源与混合流同步传播 |
| 0068 | [编写时字体角色与颜色级联](0068-theme-text-appearance.md) | 已采纳；全局及分域声明意图、单项恢复与加入时快照 |
| 0069 | [语义首页与奇偶页区](0069-semantic-page-variants.md) | 已采纳；显示页码选择、显式空对、最大预留及标准 DOCX 开关 |
| 0070 | [固定语义页面图层](0070-fixed-semantic-page-overlays.md) | 已采纳；静态页框、图层顺序、共享来源与明确转换损失 |
| 0071 | [变宽栏的最低行数](0071-minimum-lines-in-variable-columns.md) | 已采纳；有界全源重排、实际分配验证与最终诊断 |
| 0072 | [单向长表固定列横向合并](0072-single-pass-horizontal-spans.md) | 已采纳；真实物理列、完整覆盖、锚点及覆盖槽分开计费 |
| 0073 | [单向长表图像及被动外链](0073-single-pass-images-and-links.md) | 已采纳；共享最终几何、逐页交互、累计预算及显式拒绝 |
| 0074 | [合并格覆盖有界自动宽度列](0074-merged-automatic-columns.md) | 已采纳；有界首选宽度、稳定约束顺序及一次内边距 |
| 0075 | [单向长表完整行续排](0075-single-pass-row-continuation.md) | 已采纳；源行测量一次、完整行／图片切片与页内最终链接 |
| 0076 | [离线 PDF 认证签名](0076-offline-pdf-certification.md) | 已采纳；首签 DocMDP、显式三档权限及原子签名目标 |
| 0077 | [单向 PDF 的加密及静态归档](0077-single-pass-pdf-protection.md) | 已采纳；共享权限、静态归档限制与锚点字体额度 |
| 0078 | [语义表格按实际区域宽度布局](0078-variable-width-semantic-tables.md) | 已采纳；原子合并行组、独立候选预算与最终语义来源 |
| 0079 | [显式 CMS 校验与 PDF 字节覆盖分离](0079-explicit-pdf-signature-verification.md) | 已采纳；有界快照、准确 ByteRange、完整性及未签追加分别报告 |
| 0080 | [单向长表实际页码与准确声明总数](0080-single-pass-page-fields.md) | 已采纳；实际呈现解析、总数严格核对、来源及生成预算分开 |
| 0081 | [行内内容内表复用有界自动列宽](0081-nested-inline-automatic-columns.md) | 已采纳；父格实际宽度、完整探测来源及原生 DOCX 声明 |
| 0082 | [完整格源按实际变宽区域续排行](0082-variable-width-inline-continuation.md) | 已采纳；完整来源、实际区域容量、图片字段及独立工作／呈现预算 |
| 0083 | [可编辑 XML 源码与普通资源 ZIP](0083-editable-xml-template-bundles.md) | 已采纳；复用现有模板编译／绑定、源码保留、显式资源及原子保存 |
| 0084 | [将语义模型映射到既有 XML 模板](0084-semantic-model-to-existing-xml.md) | 已采纳；可读 XML、文字原样模式、显式报损、资源所有权及多轮编辑 |
| 0085 | [XML 页区共享来源与模型保存](0085-shared-xml-page-region-sources.md) | 已采纳；同类静态引用、唯一源预算及变体／图层保存 |
| 0086 | [XML 完整语义绑定与注释保存](0086-complete-xml-semantic-binding.md) | 已采纳；完整文档／注释结果、关系校验、Caption 及文字外链保存 |
| 0087 | [标准文件与准确绑定的 XML 恢复伴随包](0087-bound-standard-output-recovery.md) | 已采纳；准确字节及资源绑定、当前文件回退、有损导出禁止源快照和历史损失累计 |
| 0088 | [HTML 内嵌有界模型恢复包](0088-embedded-html-model-recovery.md) | 已采纳；单文件注释封装、准确当前 HTML 绑定、显式回退和总编码预算 |
