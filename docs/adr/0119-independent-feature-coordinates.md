# ADR-0119：按需引入文档能力

- 状态：已采纳
- 日期：2026-11-24

## 背景

单格式导出、矢量公式、语义 HTML、PDF 签名与模型恢复、OpenType 段落曾共用较宽的依赖边界。单独声明某个功能的坐标也会解析无关解析器、输出适配器或签名库。`layout-core` 的两个方法还要求 `DisplayListBuilder`，调用方的编译类路径却缺少提供该类型的 `graphics-core`。

## 决定

- `layout-core` 通过 `api` 传递公开签名使用的 `graphics-core`。API 检查只构建发布库 JAR。
- `starter-pdf` 提供 `com.mirakyux.export4j.pdf.PdfExport`。`adapter-docx` 提供 `com.mirakyux.export4j.adapter.docx.DocxExport`。两个入口接收现有 `Document`，模板和 Web 适配器按输入选择。完整 `starter` 继续提供从源内容导出两种格式的统一入口。
- `font-core` 定义字体类型、解析 SPI 和缓存。`font-fontbox` 提供基于 FontBox 的解析实现。普通 PDF、组件和矢量公式在运行时传递此实现，直接使用 `font-core` 解析文件的调用方需添加实现坐标。
- `math-api` 保存公式选项和错误。`math-core` 生成矢量轮廓，`math-web` 连接 Web 源内容，`math-docx` 将受支持的 TeX 转为 Word 数学模型。`math-docx-web` 提供由文本、Markdown 或语义 HTML 生成原生数学文档的入口。`math` 继续提供原跨格式编译入口。
- 语义 Web 适配器独立运行。启用 `HtmlImporter.Builder.layoutMode(true)` 时，由 `web-layout` 提供 CSS 盒布局实现。
- `pdf-signatures` 提供 PDF 签名、认证和验证，`pdf-recovery` 提供内嵌模型读写。`opentype-layout` 提供 `com.mirakyux.export4j.opentype.layout.OpenTypeText`，`opentype` 提供独立塑形。

每个 Java 包由一个库提供。模块路径运行时，将所选功能的完整依赖放入 `--module-path`，并用 `--add-modules ALL-MODULE-PATH` 解析其中的自动模块和第三方命名模块。升级时按各指南迁移包名与坐标并重新编译。API 门禁核对已登记的类型与签名，报告二进制类名变化。 [导出](../easy-export-api.md) · [公式](../math-api.md) · [PDF 签名](../pdf-standards-api.md) · [模型恢复](../model-recovery-api.md) · [OpenType](../native-opentype-api.md)

## 验收

检查各坐标在 Gradle 元数据与仅 POM 路径下的编译和运行依赖，并以独立制品执行导出和已有功能回归。坐标选择见[导出指南](../easy-export-api.md)，直接依赖见[架构](../architecture.md#模块与依赖)。
