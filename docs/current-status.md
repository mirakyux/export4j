# 版本与当前状态

本文区分已发布候选版、当前开发源码和历史验收。API 使用方法见[文档导航](README.md)，各能力的准确边界见[能力矩阵](capabilities-api.md)。

## 选择使用版本

| 版本 | 获取方式 | 使用前确认 |
| --- | --- | --- |
| `0.1.0-rc.3` | Maven Central，16 个库及 BOM | 已发布候选，尚无稳定版；依赖与变化见 [RC3 说明](releases/0.1.0-rc.3.md) |
| `develop`／`0.1.0-SNAPSHOT` | 从源码构建；默认本地仓库为 `build/repository/` | 当前包含 18 个库及 BOM，新增模块不在已发布 RC3 中；构建和消费见[发布规范](releasing.md#本地验证流程) |
| `0.0.0-verification-*` | 验收记录中指定的本地隔离仓库 | 用于锁定一次测试的制品和摘要，不是可从 Central 安装的正式版本 |

Java 基线为 21。BOM 只对齐同批模块版本；不要把开发模块与 RC3 BOM 混用。`export4j-designer` 是可选本地应用，`examples` 是示例工程，两者均不进入库发布和 BOM。

## 开发版新增入口

以下内容已经进入开发源码，但不属于 RC3 的发布范围。

| 需求 | 开发版入口 | 使用条件与证据 |
| --- | --- | --- |
| 一次配置字体与样式，导出 PDF／DOCX | [`export4j-starter`／`Export4j`](easy-export-api.md) | 字体和资源仍显式提供；XML 快捷入口只处理正文。[验收](verification/easy-export.md) |
| 直接传 DTO、record 或 JavaBean | [`DocumentTemplate.render(Object)`／`Export4j.xml(String, Object)`](xml-template-api.md#java-对象绑定) | 保留 Map 接口；读取 record 组件或公开 getter，嵌套 List／Map 共用预算；null、日期等需先整理。[验收](verification/easy-export.md#2026-11-14-dto-record-绑定补齐) |
| 按块声明字体、颜色、解析方式并继承全局配置 | [源内容块](content-blocks-api.md) | Java 和 XML 支持显式文本／Markdown／HTML 及公式、表格开关；不自动猜测内容格式。[验收](verification/content-blocks.md) |
| 公式识别与跨格式表示 | [`export4j-math`](math-api.md)和[混合内容编译](rich-content-api.md) | 受限 TeX／Markdown 入口、PDF 矢量与 Word 原生数学；不是 OCR 或完整 LaTeX 执行器。[验收](verification/rich-content.md) |
| 重复导出时复用字体，空闲后释放 | [字体缓存](font-cache-api.md) | 有界缓存和解析租约，不依赖 PDFBox 的系统字体磁盘缓存；[资源生命周期验收](verification/font-cache.md) |

浏览器[模板编辑工具](documentation-site.md#浏览器模板编辑)是文档站功能，使用 XML＋JSON 和近似预览；Java 对象绑定在 JVM 中执行。完整 Java 引擎预览、资源包和导出使用[本地设计器](designer.md)。

## 使用前需确认的限制

- 公式文字字体合计超过 64 MiB 的错误分类尚有缺陷；显式配置 `TEXT` 候选可能吞掉该预算错误，默认无候选仍严格失败。复现范围和处理状态见[统一入口已知限制](verification/easy-export.md#已知限制)。
- starter 尚无任意具名字体角色的登记入口；需要此能力时使用底层 `DocumentEngine.fontRole(...)`，不要依赖内部 `family_N` 名称。
- PDF／DOCX／HTML 等格式的支持按具体入口分别定义，DOCX 和 PDF 不保证相同分页。现有业务迁移仍需使用真实模板、字体、图片和容量重新验收。

平台覆盖、稳定 API／版本及更广输入兼容的待办，以[能力缺口表](capability-gaps.md)为准；推进顺序见[执行计划](remaining-capabilities-plan.md)。

## 如何阅读验收资料

最近一次对象绑定验收记录于 2026-11-14：2,714 项 Java 测试、四路隔离消费、35 份 PDF 和 30 份 DOCX 独立检查通过，记录见[统一入口验收](verification/easy-export.md#2026-11-14-dto-record-绑定补齐)。这些数字对应该批源码和本地制品，不代表此后的提交已重新执行同一验收，也不代表远程发布。

[验收导航](verification/README.md)按主题连接实际证据；阶段文件保留各自日期、输入、工具与限制。[ADR](adr/README.md)解释设计取舍，“已采纳”不等于已经完成全部输出验收。发布到 Central、文档站部署和搜索引擎收录是分别验证的结果。
