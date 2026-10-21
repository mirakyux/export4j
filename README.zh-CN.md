# Export4j

Java 文档排版与生成引擎  
A Java Document Layout and Generation Engine

[English](README.md) | **简体中文**

Export4j 是用于生成报告和结构化文档的开源 Java 引擎。通过链式 Java API 或离线 XML 模板编写内容，由共享布局引擎生成 PDF、SVG 和 PNG；独立适配器将文档语义映射为 DOCX 子集、自包含 HTML 和 CommonMark/GFM Markdown。

**当前处于早期开发阶段，尚未发布稳定制品，API 可能调整。** 受支持的 PDF 报告和密集静态表单已具备限定范围的试用条件，具体证据与限制见[就绪评估](docs/readiness-assessment.md)和[能力矩阵](docs/capabilities-api.md)。

- Java 21 LTS · Gradle Wrapper · Apache-2.0。
- 显式字体和资源，生成 PDF 无需浏览器。
- 文档语义、布局算法和输出后端保持独立。

## 目录

- [功能特性](#功能特性)
- [快速开始](#快速开始)
- [Java 使用示例](#java-使用示例)
- [XML 模板](#xml-模板)
- [HTML 与 Markdown 导出](#html-与-markdown-导出)
- [格式支持与限制](#格式支持与限制)
- [待完成与路线图](#待完成与路线图)
- [架构与模块](#架构与模块)
- [文档导航](#文档导航)
- [构建与验证](#构建与验证)
- [参与贡献](#参与贡献)
- [开源致谢](#开源致谢)
- [许可证](#许可证)

## 功能特性

| 领域 | 当前能力 |
| --- | --- |
| 文档编写 | 链式 Builder、标题、段落、富文本、列表、可复用主题与命名样式 |
| 文字与字体 | 显式字体组及真实变体、缺字策略、基于 JDK 的 OpenType 塑形、双向文本、两端对齐和可选严格中文禁则子集 |
| 页面布局 | 自动分页、分节、页眉页脚、页面背景、多栏、矩形绕排及区域流组件 |
| 表格 | 重复表头、受支持的自动列宽、跨行／跨列合并、显式拆行续页、多段单元格及两层原子内表 |
| 图片与绘制 | 行内 PNG／JPEG、矢量路径、形状、水印及受限离线 SVG 输入子集 |
| 导航与表单 | 目录、书签、链接、评论、页码／引用字段、脚注续页、静态表单组件及四类基础可编辑 PDF 字段 |
| 模板 | 离线 XML 变量、条件、循环、动态行、主题、静态页面配置、图文／表格页眉页脚、分节、平面 include 和字段；严格轻量 Markdown |
| 输出格式 | PDF、自包含 SVG、PNG 单页／ZIP、DOCX 语义子集、HTML 和 Markdown |
| PDF 处理 | 有界静态页检查、文字提取、选页及合并；AES-256 输出、PDF/A-2b 子集及外部 detached CMS 签名回调 |
| 长表与执行控制 | 单向普通／富文本行源、同步逐页输出、资源预算、取消、超时、结构化诊断和原子路径写入 |

各项能力都有明确边界。组件网格、语义表格和单向长表分别服务不同契约，选择入口时参阅[编写指南](docs/authoring-api.md)、[链式 API](docs/fluent-api.md)与[长表指南](docs/streaming-table-api.md)。

## 快速开始

前置条件：**JDK 21**。首次构建需要访问 Gradle 分发服务和 Maven Central，无需全局安装 Gradle。

```shell
git clone https://github.com/mirakyux/export4j.git
cd export4j
```

Windows PowerShell：

```powershell
.\gradlew.bat build
.\gradlew.bat :examples:authoring :examples:xmlTemplates :examples:production
```

Linux/macOS：

```sh
bash ./gradlew build
bash ./gradlew :examples:authoring :examples:xmlTemplates :examples:production
```

| 示例任务 | 输出目录 | 展示内容 |
| --- | --- | --- |
| `:examples:authoring` | `examples/build/authoring/` | Java 编写、文字排版与语义 DOCX |
| `:examples:xmlTemplates` | `examples/build/xml-templates/` | XML 报告模板与数据绑定 |
| `:examples:xmlPageRegions` | `examples/build/xml-page-regions/` | XML 页眉页脚主题，以及含目录的最终页码 |
| `:examples:xmlSections` | `examples/build/xml-sections/` | 图片／表格页区、分节纸张／页码和原生 DOCX 严格往返 |
| `:examples:webExports` | `examples/build/web-exports/` | HTML／Markdown、嵌入图片及按功能报错／警告／忽略 |
| `:examples:production` | `examples/build/production/` | 分页、分栏、绕排、表格与多格式输出 |
| `:examples:staticForms` | `examples/build/static-forms/` | 密集静态表单与矢量控件 |
| `:examples:streamingTable` | `examples/build/streaming-table/` | 单向长表输出 |

示例显式加载仓库内固定字体，运行时不下载字体。核心库不携带这些示例字体，来源与许可见[字体资源](assets/fonts/README.md)。

当前尚无可直接安装的稳定 Maven 制品。可从源码构建，或通过[隔离 Maven 制品](docs/releasing.md)及 BOM 试用当前快照。

## Java 使用示例

下面是使用公共编写 API 的完整类。运行目录为仓库根目录，classpath 需包含组件模块、PDF 后端及其运行依赖。字体路径指向仓库样例资源；接入业务时应显式提供具有相应授权的字体。

```java
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.Document;

public class QuickStart {
    public static void main(String[] args) throws IOException {
        var fonts = FontGroup.strict(List.of(
                loadFont("LiberationSans-Regular.ttf"),
                loadFont("LXGWWenKaiTC-Regular.ttf")));
        var engine = DocumentEngine.builder(fonts).build();
        var document = Document.builder()
                .heading("Quarterly report / 季度报告", 1)
                .paragraph("Create a report with explicit fonts and automatic pagination.")
                .bullet("Text, tables and document structure remain explicit.")
                .table(List.of("Metric", "Value"),
                        List.of(List.of("Revenue", "120"), List.of("Cost", "80")))
                .build();

        var output = Path.of("build/readme-report.pdf");
        Files.createDirectories(output.getParent());
        engine.write(document, new PdfBackend(), output);
    }

    private static FontFace loadFont(String name) throws IOException {
        try (var input = Files.newInputStream(Path.of("assets/fonts", name))) {
            return TrueTypeFontLoader.load(input, TrueTypeFontLoader.DEFAULT_MAX_BYTES);
        }
    }
}
```

结果写入 `build/readme-report.pdf`。`DocumentEngine` 负责布局，`PdfBackend` 绘制最终结果；字体选择始终显式，严格策略下缺字会明确失败。

可直接通过 Gradle 运行的完整样例见 [AuthoringExample](examples/src/main/java/com/mirakyux/export4j/examples/AuthoringExample.java)，更多编写方式见[编写指南](docs/authoring-api.md)。

## XML 模板

将报告结构写入 XML，由 Java 提供数据、字体、图片和输出配置：

```xml
<document version="1" title="${title}">
  <page size="A4" margin="36"/>
  <heading level="1">${title}</heading>
  <each items="items" as="item">
    <p>${item.name}: ${item.result}</p>
  </each>
</document>
```

模板编译到既有文档模型。资源须显式登记，所有分支都会在编译时检查，编译器不自行读取文件或网络资源；未知语法和不安全 XML 明确拒绝。

XML 支持独立主题的图文／表格页眉页脚及最终页码字段。单节使用 `template.renderWithPageRegions(data)`，分节使用 `template.renderSections(data)`，可配置各节纸张、页码和页区继承／覆盖。两者均可交给 `engine.export(document)`，也能导出原生 DOCX 页眉页脚。见[页区](docs/xml-page-regions-api.md)、[分节](docs/xml-sections-api.md)和 [DOCX 映射](docs/docx-page-regions-api.md)。

参阅 [XML 指南](docs/xml-template-api.md)、[纸张与边距](docs/xml-page-settings-api.md)、[完整报告模板](examples/src/main/resources/templates/report.xml)和[Java 调用](examples/src/main/java/com/mirakyux/export4j/examples/XmlTemplateExample.java)。

## HTML 与 Markdown 导出

通过项目 BOM 添加 `com.mirakyux.export4j:export4j-adapter-web`。适配器直接消费语义模型，生成文件无需浏览器：

```java
new HtmlAdapter().export(document).to(Path.of("build/report.html"));
var policy = ExportPolicy.builder()
        .defaultAction(UnsupportedAction.WARN)
        .on(ExportFeature.CUSTOM_BLOCK, UnsupportedAction.ERROR)
        .build();
var result = MarkdownAdapter.builder().policy(policy).build()
        .export(document).to(Path.of("build/report.md"));
```

适配器类型从 `com.mirakyux.export4j.adapter.web` 导入，`Path` 为 `java.nio.file.Path`。默认动作是 `ERROR`；选择 `WARN` 时应用明确降级并返回结构化诊断，`IGNORE` 使用同样降级但不生成警告；可按功能覆盖。非法输入、超限、取消和 I/O 失败始终报错，详见[完整支持与降级矩阵](docs/web-export-api.md)。

HTML 保留语义样式、嵌入图片及合并／嵌套表格。Markdown 输出 CommonMark/GFM 内容，对无法表达的几何或表结构执行策略。阅读器决定换行和分页，部分阅读器禁用 data 图片或生成的行内 HTML。导出与严格 Markdown 模板输入语法独立。运行 `:examples:webExports` 可生成两种格式。

## 格式支持与限制

| 格式 | 当前范围 |
| --- | --- |
| PDF | 主要报告输出，支持已声明的导航、表单及安全能力；另有受限静态页适配器 |
| SVG／PNG | 复用共享 Display List 的静态输出；非空交互数据明确拒绝 |
| DOCX | 明确的语义子集，包含受支持文字、表格、图片、编号、字段和各节默认页眉页脚；不支持项按策略拒绝或返回诊断 |
| HTML | 语义 HTML5、内联 CSS、嵌入图片、合并／嵌套表格及显式损失策略 |
| Markdown 输出 | CommonMark/GFM 内容；不支持的几何／结构按报错、警告或忽略策略处理 |
| XML／Markdown 输入 | 有明确语法和预算的离线编写输入；XML 不等同于 HTML/CSS |

- 不保证一般 Word 文档无损往返，也不保证 DOCX 与 PDF 分页相同。
- 复杂文字、提取、搜索与选择受已测脚本和阅读器范围约束，不宣称完整 OpenType、完整 Unicode 分段或 PDF/UA 合规。
- PDF/A 文件需要独立合规验证；签名要求调用方提供外部签名器，项目不捆绑私钥。
- 固定字段与原子内容必须放入预留区域，不通过隐式缩小或裁切处理不支持的内容。
- 单向布局有界，但 PDFBox 页树仍增长，不代表完整 PDF 恒内存或长期无泄漏。
- 资源预算不等于恶意输入沙箱；调用方仍需控制资源访问及不可信输入隔离。

## 待完成与路线图

以下能力仍未完整实现，不属于当前已支持特性：

- 完整字体角色／颜色级联、更多页区变体，以及 XML 背景／前景声明。
- 连续富文本组件和更广泛的单元格递归续排。
- 更多复杂脚本与阅读器互操作，包括搜索和文本选择。
- 有明确范围的 HTML/CSS 输入与可视化模板设计器。
- 稳定公共 API、Maven Central 分发及可重复的跨平台发布验证。

优先级、已有子集与验收条件以[能力缺口表](docs/capability-gaps.md)、[剩余计划](docs/remaining-capabilities-plan.md)和[研发路线](docs/roadmap.md)为准。OCR 不在当前范围内。

## 架构与模块

```text
Document Model → Layout Engine → Layout IR → Display List → PDF / SVG / Raster
DOCX / OOXML  ↔ DOCX Adapter   ↔ Document Model
Document Model → Web Adapter → HTML / Markdown
```

布局引擎负责换行与分页，后端消费最终绘制指令；DOCX 适配器直接处理文档语义。

| 模块 | 职责 |
| --- | --- |
| `export4j-model` | 文档语义、样式与执行契约 |
| `export4j-font-core`、`export4j-text-core` | 字体资源、度量、塑形和文字布局 |
| `export4j-graphics-api`、`export4j-graphics-core` | 绘制接口、Display List 与通用绘制 |
| `export4j-layout-model`、`export4j-layout-core` | Layout IR、测量、区域与分页 |
| `export4j-components` | 链式编写、组合组件与 DocumentEngine |
| `export4j-backend-pdf`、`export4j-backend-svg`、`export4j-backend-raster` | 输出后端 |
| `export4j-adapter-docx`、`export4j-adapter-template`、`export4j-adapter-pdf`、`export4j-adapter-web` | 格式与模板适配器 |
| `export4j-bom`、`examples` | 版本对齐与可执行示例 |

Java 根包名与 Gradle group 为 `com.mirakyux.export4j`。依赖边界及设计决定见[架构设计](docs/architecture.md)和 [ADR](docs/adr/README.md)。

## 文档导航

详细指南目前主要使用中文。

| 主题 | 指南 |
| --- | --- |
| 开始使用 | [常用编写](docs/authoring-api.md) · [链式 API](docs/fluent-api.md) · [主题](docs/themes-api.md) |
| 支持范围 | [能力矩阵](docs/capabilities-api.md) · [就绪评估](docs/readiness-assessment.md) |
| 文字与布局 | [字体／文字](docs/text-api.md) · [布局](docs/layout-api.md) · [连续分栏](docs/continuous-columns-api.md) |
| 表格 | [语义表格](docs/table-api.md) · [块格与内表](docs/block-cells-api.md) · [单向长表](docs/streaming-table-api.md) |
| 表单与导航 | [静态表单](docs/static-forms-api.md) · [可编辑表单](docs/form-styles-api.md) · [行内字段](docs/inline-fields-api.md) |
| 模板 | [XML](docs/xml-template-api.md) · [Markdown](docs/markdown-template-api.md) · [XML 主题](docs/xml-themes-api.md) |
| 适配器 | [HTML／Markdown 导出](docs/web-export-api.md) · [DOCX 页区](docs/docx-page-regions-api.md) · [DOCX 字段](docs/docx-fields-api.md) · [静态 PDF](docs/pdf-adapter-api.md) |
| 工程维护 | [开发规约](docs/development.md) · [验收记录](docs/verification) · [发布规范](docs/releasing.md) |

## 构建与验证

`build` 聚合模块构建、JUnit 测试、Javadoc 与源码包。工具版本和解析依赖由版本目录、Wrapper 配置、锁文件及校验元数据固定。

```powershell
.\gradlew.bat build sbom publishLocalBundle
powershell -NoProfile -File scripts/verify_local_consumer.ps1
```

`publishLocalBundle` 将库和 BOM 写入 `build/repository`，隔离调用方通过 Maven 坐标解析这些制品并实际生成文档；该过程不发布到 Maven Central，也不写用户 Maven 仓库。

独立格式及视觉验证使用 Python 3.11+、隔离环境中安装的[固定验证依赖](scripts/requirements-verification.txt)及 Poppler；部分检查另需 veraPDF、OpenSSL 或 Word。按[验证矩阵](.agents/skills/develop-export4j/references/verification.md)选择受影响功能的检查，不为获得通过结果自动刷新视觉基线。

GitHub Actions 工作流采用手动触发，仅覆盖完整验收流程的一部分；此处不宣称已获得托管 CI 的验证结果。

## 参与贡献

欢迎缺陷报告、可复现样例、测试、文档和范围明确的功能贡献。开始前阅读[贡献指南](CONTRIBUTING.md)、[开发规约](docs/development.md)和 [AGENTS.md](AGENTS.md)，共享项目技能见 [.agents](.agents/README.md)。

缺陷与建议使用 [GitHub Issues](https://github.com/mirakyux/export4j/issues)。安全问题请通过 [GitHub 私密漏洞报告](https://github.com/mirakyux/export4j/security/advisories/new)提交，详见[安全政策](SECURITY.md)。

## 开源致谢

Export4j 复用了以下开源基础设施与显式提供的字体资源：

| 项目／资源 | 用途 |
| --- | --- |
| [Apache PDFBox／FontBox](https://pdfbox.apache.org/) | PDF 后端、受限 PDF 处理与字体解析 |
| [Apache Commons Logging](https://commons.apache.org/proper/commons-logging/) | PDFBox 传递运行依赖 |
| [OpenJDK](https://openjdk.org/) | Java 运行时、Java2D／OpenType、ZIP 与 XML 基础设施 |
| [Gradle](https://gradle.org/)、[JUnit](https://junit.org/) | 构建与 Java 测试 |
| [pypdf](https://github.com/py-pdf/pypdf)、[pypdfium2](https://github.com/pypdfium2-team/pypdfium2)、[Pillow](https://python-pillow.org/)、[python-docx](https://github.com/python-openxml/python-docx)、[cryptography](https://cryptography.io/)、[Poppler](https://poppler.freedesktop.org/) | 独立格式、渲染与验收工具 |
| [Liberation Fonts](https://github.com/liberationfonts/liberation-fonts)、[霞鹜文楷 TC](https://github.com/lxgw/LxgwWenkaiTC)、[站酷小薇](https://github.com/googlefonts/zcool-xiaowei) | 示例与测试显式字体 |
| [Noto Fonts](https://github.com/notofonts/noto-fonts)、[Source Sans 3](https://github.com/adobe-fonts/source-sans) | 复杂脚本及 CFF 示例／测试字体 |

具体版本、哈希、来源和分发义务见[依赖记录](docs/dependencies.md)、[字体记录](assets/fonts/README.md)、[NOTICE](NOTICE)及[原始许可证](third-party/licenses)。

## 许可证

项目自研代码与文档采用 [Apache License 2.0](LICENSE)。第三方依赖、字体及其他资源保留各自许可证，不因随项目分发而改为 Apache-2.0。
