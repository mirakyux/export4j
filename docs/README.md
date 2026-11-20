# Export4j 文档

Export4j 支持通过 Java 或模板编写文档，也提供已有文件的导入、编辑和转换。首次使用从[中文 README](../README.zh-CN.md)或 [English README](../README.md)开始，运行方式及输出目录见[示例索引](examples.md)。

选型、运行环境、字体、转换限制和许可证见[常见问题](faq.zh-CN.md)或 [English FAQ](faq.md)。

## 首次接入

| 目标 | 阅读顺序 |
| --- | --- |
| 使用 Maven Central 中的候选版本 | [发布版快速开始](../README.zh-CN.md#快速开始) → [常用编写](authoring-api.md) |
| 评估当前开发版，减少调用配置 | [当前状态](current-status.md) → [统一 PDF／DOCX 入口](easy-export-api.md) → [开发版示例](examples.md#开发版示例) |
| 把业务对象绑定到 XML 模板 | [XML 模板](xml-template-api.md) → [DTO／record 绑定规则](xml-template-api.md#java-对象绑定) |
| 先查看效果或编辑模板 | [示例索引](examples.md) → [本地设计器](designer.md) |

已发布 RC3 与当前 `develop` 的模块和 API 范围不同，开发版指南中的新增能力不能直接套用到 RC3 依赖。

## 按任务阅读

| 任务 | 指南 |
| --- | --- |
| 配置一次，直接导出 | [统一 PDF／DOCX 入口](easy-export-api.md) |
| 编写报告与表单 | [常用编写](authoring-api.md) · [源内容块与继承](content-blocks-api.md) · [链式 API](fluent-api.md) · [组件](components-api.md) · [静态表单](static-forms-api.md) |
| 配置字体和样式 | [字体／文字](text-api.md) · [字体缓存](font-cache-api.md) · [主题](themes-api.md) · [原生 OpenType](native-opentype-api.md) · [字体来源与许可](../assets/fonts/README.md) |
| 调整布局与页区 | [布局](layout-api.md) · [可编辑栅格](grid-layout-api.md) · [正文分栏与通栏图表](column-flow-api.md) · [连续分栏组件](continuous-columns-api.md) · [轮廓浮动](contour-floats-api.md) · [页区变体](page-variants-api.md) · [背景／前景](page-overlays-api.md) |
| 编写表格和长表 | [语义表格](table-api.md) · [合并列](column-spans-api.md) · [合并行](row-spans-api.md) · [块格与内表](block-cells-api.md) · [单向长表](streaming-table-api.md) · [重放与磁盘索引 PDF](streaming-replay-api.md) |
| 添加导航和字段 | [行内字段](inline-fields-api.md) · [可编辑表单](form-styles-api.md) · [可保存导航](portable-navigation-api.md) |
| 使用可视化模板设计器 | [HTML 设计器](designer.md) · [容器内部编辑](designer.md#容器内部编辑) · [变量与数组绑定](designer.md#变量与数组绑定) |
| 使用 XML／Markdown 模板 | [XML](xml-template-api.md) · [模板主题](xml-themes-api.md) · [页区](xml-page-regions-api.md) · [分节](xml-sections-api.md) · [严格 Markdown 模板](markdown-template-api.md) |
| 保存模板和恢复文档 | [模板资源 ZIP](xml-template-bundle-api.md) · [语义模型保存为 XML](xml-model-saving-api.md) · [可选文档模型恢复](model-recovery-api.md) |
| 导入、编辑与互转 | [统一转换](document-conversion-api.md) · [语义编辑及损失报告](semantic-editing-api.md) · [通用 Markdown 导入](markdown-import-api.md) · [HTML/CSS 导入](html-import-api.md) · [HTML／Markdown 导出](web-export-api.md) |
| 处理公式与混合内容 | [混合内容与跨格式公式](rich-content-api.md) · [TeX 与 Markdown 公式](math-api.md) · [Word 原生数学树](docx-math-api.md) · [行内矢量](inline-vectors-api.md) |
| 处理 DOCX | [原生语义与编辑](docx-semantic-api.md) · [原生数学公式](docx-math-api.md) · [原包文字编辑](docx-editing-api.md) · [页区](docx-page-regions-api.md) · [字段](docx-fields-api.md) · [样式导入](docx-style-import-api.md) |
| 处理已有 PDF | [入口与边界](pdf-adapter-api.md) · [页面](pdf-page-editing-api.md) · [对象](pdf-object-editing-api.md) · [组合](pdf-object-composition-api.md) · [渲染／图片](pdf-raster-api.md) · [优化](pdf-optimization-api.md) |
| 修改和恢复 PDF 内容 | [文字替换](pdf-text-editing-api.md) · [图片替换](pdf-image-editing-api.md) · [两种脱敏模式](pdf-redaction-api.md) · [语义恢复](pdf-semantic-import-api.md) · [源字体兼容](pdf-font-compatibility-api.md) · [被动结构兼容](pdf-passive-structures-api.md) |
| 使用 PDF 标准与签名 | [PDF/A／PDF/UA／PAdES](pdf-standards-api.md) · [认证与 DocMDP](pdf-certification-api.md) · [已有签名校验](pdf-signature-verification-api.md) · [表单数据](pdf-form-data-api.md) |
| 使用高级图形 | [色彩、合成与 SVG 资源](advanced-graphics-api.md) |

每个 API 指南分别说明支持的输入、调用方式、资源所有权、损失策略和失败边界。XML／严格 Markdown 模板与通用文档导入是不同入口；SVG／PNG 资源加载也不等于已有文档的语义导入。

## 当前能力与计划

| 需要确认的问题 | 事实入口 |
| --- | --- |
| 使用的是发布版还是开发版、有哪些已知限制 | [当前状态](current-status.md) |
| 目前能做什么、使用哪个 API | [能力矩阵](capabilities-api.md)及对应指南 |
| 哪些场景适合试用 | [就绪评估](readiness-assessment.md) |
| 还缺什么、每项何时算完成 | [能力缺口表](capability-gaps.md) |
| 接下来如何推进 | [执行计划](remaining-capabilities-plan.md) · [路线图](roadmap.md) |
| 一项能力实际做过哪些验证 | [按主题查验收](verification/README.md) · [完整记录目录](verification) |

缺口表维护当前待完成项，阶段记录保留当时的样例、限制及结果，不直接作为当前待办清单；对应功能指南链接其验收记录。

## 开发与维护

| 主题 | 入口 |
| --- | --- |
| 参与贡献 | [贡献指南](../CONTRIBUTING.md) · [开发规约](development.md) |
| 理解模块、调用链和资源归属 | [架构总览](architecture.md) · [运行路径](architecture.md#主要运行路径) · [生命周期](architecture.md#状态与资源生命周期) |
| 定位扩展点与设计取舍 | [维护入口](architecture.md#扩展与维护入口) · [架构决策记录](adr/README.md) |
| 参考架构与优化取舍 | [Fesod、x-easypdf 源码研究](architecture-reference-review.md) |
| 构建与输出验证 | [开发中的验证流程](development.md#测试策略) · [验证矩阵](../.agents/skills/develop-export4j/references/verification.md) |
| API 兼容和发布 | [兼容规则](api-compatibility.md) · [发布规范](releasing.md) |
| 依赖和授权 | [依赖记录](dependencies.md) · [字体记录](../assets/fonts/README.md) · [NOTICE](../NOTICE) · [第三方许可证](../third-party/licenses) |
| 历史设计背景 | [立项文档](project-proposal.md)及各 ADR |

使用指南说明运行条件和调用契约，架构与开发规约说明模块边界；验收记录保留工具版本、实际应用动作及结果边界。
