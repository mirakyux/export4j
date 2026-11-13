# Export4j — Java PDF 生成与文档导出

Java 文档排版与生成引擎  
A Java Document Layout and Generation Engine

[English](README.md) | **简体中文**

[文档站](https://export4j.mirakyux.com/zh/) · [快速开始](https://export4j.mirakyux.com/zh/getting-started) · [常见问题](https://export4j.mirakyux.com/zh/docs/faq)

Export4j 是面向报告、发票、表单和结构化文档的开源 **Java PDF 生成与文档导出库**。通过链式 Java API 或 XML 模板编写文字与表格、配置显式字体，生成自动分页的 PDF，也可将受支持的内容导出为 DOCX、HTML、Markdown、SVG 和 PNG。

可导入明确子集内的 PDF、DOCX、HTML 和 Markdown 内容，编辑后转换为其他格式，并对不兼容特性执行明确策略。可保存 XML 模板，也可恢复已保存的受支持文档模型，继续编辑与导出。

**候选版本 [`0.1.0-rc.2` 已发布到 Maven Central](https://central.sonatype.com/artifact/com.mirakyux.export4j/export4j-bom/0.1.0-rc.2)。** 当前尚无稳定版，API 仍可能调整。接入前请查看[版本说明](docs/releases/0.1.0-rc.2.md)、[格式限制](#格式支持与限制)和[能力矩阵](docs/capabilities-api.md)。

## 目录

- [功能概览](#功能概览)
- [快速开始](#快速开始)
- [HTML 模板设计器](#html-模板设计器)
- [Java 使用示例](#java-使用示例)
- [XML 模板](#xml-模板)
- [HTML 与 Markdown 导出](#html-与-markdown-导出)
- [格式支持与限制](#格式支持与限制)
- [常见问题](docs/faq.zh-CN.md)
- [待完成与路线图](#待完成与路线图)
- [文档导航](#文档导航)
- [构建与验证](#构建与验证)
- [参与贡献](#参与贡献)
- [开源致谢](#开源致谢)
- [许可证](#许可证)

## 功能概览

| 领域 | 已有能力 |
| --- | --- |
| 报告与表单 | 标题、富文本、列表、图片、主题、页眉页脚、页码、目录、书签、链接及静态／可编辑表单 |
| 分页与表格 | 分节、分栏、浮动内容、重复表头、合并单元格、嵌套表格及跨页续排 |
| 导入、编辑与转换 | PDF 语义恢复、DOCX 语义编辑与原包文字编辑、HTML/CSS 和 Markdown 导入；损失预检及报错／警告／忽略策略 |
| 模板与复用 | XML 变量、条件、循环和 include；可编辑 XML／资源 ZIP、可选 HTML 模板设计器及文档模型恢复 |
| 文字与图形 | 显式字体、复杂文字、矢量图形、CMYK／ICC／专色、透明度和渐变；可选原生 OpenType 提供可变／彩色字体及竖排 |
| PDF 处理 | 页面／对象编辑、组合、渲染、图片提取、优化、文字／图片替换、两种脱敏模式、表单数据及独立 XFA 子集 |
| PDF 安全与标准 | AES-256、选定 PDF/A 等级、PDF/UA-1 及 PAdES B／T／LT／LTA；密钥、信任和验证资料显式提供 |
| 大文档 | 流式长表、用于总数／引用的显式重放、可选磁盘索引 PDF、执行预算、取消及原子文件输出 |

各项能力适用于文档声明的输入子集，配置与示例见 [API 指南](docs/README.md)。

## 快速开始

环境要求：**JDK 21**，生成文档无需安装 Office 或浏览器。通过已发布的 BOM 对齐模块版本；以下依赖可运行下方的 PDF 示例。

Maven：

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>com.mirakyux.export4j</groupId>
      <artifactId>export4j-bom</artifactId>
      <version>0.1.0-rc.2</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
<dependencies>
  <dependency>
    <groupId>com.mirakyux.export4j</groupId>
    <artifactId>export4j-components</artifactId>
  </dependency>
  <dependency>
    <groupId>com.mirakyux.export4j</groupId>
    <artifactId>export4j-backend-pdf</artifactId>
  </dependency>
</dependencies>
```

Gradle Kotlin DSL：

```kotlin
repositories { mavenCentral() }
dependencies {
    implementation(platform("com.mirakyux.export4j:export4j-bom:0.1.0-rc.2"))
    implementation("com.mirakyux.export4j:export4j-components")
    implementation("com.mirakyux.export4j:export4j-backend-pdf")
}
```

其他适配器和后端按需加入，BOM 统一约束全部 16 个发布库。可选模板设计器通过源码运行，不包含在 BOM 中。

运行仓库示例时克隆源码。首次构建需要访问 Gradle 分发与 Maven Central；仓库已包含 Gradle Wrapper。

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

| 示例 | 输出目录 | 演示内容 |
| --- | --- | --- |
| `:examples:authoring` | `examples/build/authoring/` | Java 编写与语义 DOCX |
| `:examples:xmlTemplates` | `examples/build/xml-templates/` | XML 报告与数据绑定 |
| `:examples:manuWeave` | `examples/build/manuweave/` | ManuWeave 全部 286 个学术模板配置，各自输出 PDF 与格式差异报告 |
| `:examples:manuWeaveMath` | `examples/build/manuweave-math/` | PDF／SVG 矢量数学排版与 Word 原生可编辑公式 |
| `:examples:production` | `examples/build/production/` | 分页、分栏、表格与多格式输出 |
| `:examples:documentConversion` | `examples/build/document-conversion/` | 导入、编辑、损失预检及导出 |
| `:examples:staticForms` | `examples/build/static-forms/` | 密集静态表单 |
| `:examples:streamingTable` | `examples/build/streaming-table/` | 单向长表输出 |

更多任务见[示例索引](docs/examples.md)。示例加载仓库内固定字体；业务应用应提供具有相应授权的字体，来源与许可见[字体记录](assets/fonts/README.md)。可选[原生 OpenType 模块](docs/native-opentype-api.md)还需配置目标平台的原生制品。

源码构建默认使用开发版本 `0.1.0-SNAPSHOT`。已发布候选版、本地快照试用与发布验证见[发布指南](docs/releasing.md)。

## HTML 模板设计器

运行 `.\gradlew.bat :export4j-designer:run`，在浏览器打开终端显示的本地地址。这个可选独立应用采用 IDE 式结构／资源管理器、编辑标签和属性检查器，支持纸张／边距、页眉页脚、表格预设、画布原位富文本、类型化属性及 XML／JSON 高亮。便捷编辑即时显示模板内容，精确排版显示 Java 引擎绑定数据后的真实分页。组件默认自动占位，拖动按插入线排序并保留动态绑定；段落、图片段落和表格可显式切为固定位置、缩放、旋转及自适应框尺寸，正文可选择遮盖或上下避让。粘贴图片随模板保存。编辑后由 Java 引擎实时分页预览；连续画布支持列表整体选框、多选拖动、批量属性、右键操作和图片缩放。另支持撤销／重做、模板 ZIP 保存重开及带损失预检的六格式导出。见[设计器指南](docs/designer.md)、[业务样例](docs/verification/phase-108-designer-and-business-scenarios.md)、[专用编辑验收](docs/verification/phase-109-designer-editing-controls.md)和[画布与交互验收](docs/verification/phase-110-designer-canvas-and-interactions.md)。

## Java 使用示例

在配置好上述依赖的应用中，将完整类保存为 `src/main/java/QuickStart.java`。在运行目录下的 `assets/fonts/` 中准备 `LiberationSans-Regular.ttf` 和 `LXGWWenKaiTC-Regular.ttf`，或将路径改为自行提供的授权字体。[仓库字体样例](assets/fonts/README.md)提供这两个示例文件；业务应用始终显式提供字体。

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

结果写入 `build/readme-report.pdf`。如需直接运行而不手动配置 classpath，使用 `:examples:authoring`；源码见 [AuthoringExample](examples/src/main/java/com/mirakyux/export4j/examples/AuthoringExample.java)，更多编写方式见[编写指南](docs/authoring-api.md)。

## XML 模板

将文档结构写入 XML，由 Java 提供数据、字体、图片和输出配置：

```xml
<document version="1" title="${title}">
  <page size="A4" margin="36"/>
  <heading level="1">${title}</heading>
  <each items="items" as="item">
    <p>${item.name}: ${item.result}</p>
  </each>
</document>
```

模板支持变量、条件、循环、表格、分节、页区和 include。XML 源码与资源可保存为普通 ZIP；受支持的 Java 模型也可保存为可编辑 XML。参阅 [XML 指南](docs/xml-template-api.md)、[模板资源包](docs/xml-template-bundle-api.md)、[模型保存](docs/xml-model-saving-api.md)和[完整 Java 示例](examples/src/main/java/com/mirakyux/export4j/examples/XmlTemplateExample.java)。

[可编辑正文分栏](docs/column-flow-api.md)支持 1～3 栏连续正文、宽度比例和中间通栏表格／图片。XML／ZIP 保留结构；DOCX／Web 转换当前须明确降级为顺序正文并报告布局损失。

[可编辑栅格](docs/grid-layout-api.md)从可视化布局和空白内容格开始，支持把已有内容并排、拖分隔线调宽、拆分及合并；数值轨道设置收进高级选项。文字、表格和图片通过 XML／ZIP 保留原始结构；同一行整体分页，可放在连续分栏之间。

[文档模型恢复](docs/model-recovery-api.md)为可选能力，可使用 XML／资源伴随包，或内嵌到受支持的输出。恢复信息会增加体积，外部编辑可能使其失效或被移除；绑定检查、兼容性及转换损失见该指南。

## HTML 与 Markdown 导出

通过项目 BOM 使用 `export4j-adapter-web`。下面的 `document` 为 Java 示例中的语义文档；适配器类型从 `com.mirakyux.export4j.adapter.web` 导入，`Path` 为 `java.nio.file.Path`。

```java
new HtmlAdapter().export(document).to(Path.of("build/report.html"));
var policy = ExportPolicy.builder()
        .defaultAction(UnsupportedAction.WARN)
        .on(ExportFeature.CUSTOM_BLOCK, UnsupportedAction.ERROR)
        .build();
var result = MarkdownAdapter.builder().policy(policy).build()
        .export(document).to(Path.of("build/report.md"));
```

默认动作是 `ERROR`。`WARN` 应用文档声明的降级并返回诊断；`IGNORE` 使用同样降级但不生成警告。非法输入、超限、取消和 I/O 失败始终报错，详见[支持与降级矩阵](docs/web-export-api.md)。

HTML 保留受支持的样式、嵌入图片及合并／嵌套表格；Markdown 输出 CommonMark/GFM 内容。换行和分页由目标阅读器决定。运行 `:examples:webExports` 可生成两种格式，导入后编辑与互转见[统一转换](docs/document-conversion-api.md)。

## 格式支持与限制

| 格式 | 导入／读取 | 输出／编辑 |
| --- | --- | --- |
| PDF | 有界解析与语义恢复 | 生成、页面／对象编辑、组合、渲染、优化及脱敏 |
| DOCX | 类型化 OOXML 子集 | 语义导出／编辑及原包文字编辑 |
| HTML/CSS | 离线语义模式或显式 CSS 布局子集 | 自包含语义 HTML5 |
| Markdown | CommonMark/GFM 子集；另有独立严格模板语法 | CommonMark/GFM 内容 |
| SVG／PNG | 在各自支持子集内作为矢量／图像资源加载 | 静态页面及 PNG 页 ZIP |

- 不同格式可保留的信息不同，不支持的内容按明确损失策略处理；不保证一般 Word 无损往返或 DOCX／PDF 同分页。
- [Word 原生公式](docs/docx-math-api.md)使用有类型 OMML，保留可编辑结构；独立[数学示例](docs/examples.md#数学公式)将受限 TeX 排成 PDF／SVG 矢量，无需安装 TeX。
- PDF 恢复根据文字、坐标和绘图推断可编辑结构，报告不确定性，支持显式表格网格，不执行 OCR。字体要求及整页图片降级见[恢复指南](docs/pdf-semantic-import-api.md)。
- [PDF 脱敏](docs/pdf-redaction-api.md)提供光栅化和选择性重建两种模式。前者损失文字搜索与矢量，后者保留选区外受支持的内容；调用方须定位全部敏感区域。
- 复杂文字、搜索及选择受已声明的字体、脚本和阅读器范围约束。PDF/A 和 PDF/UA 需使用对应输出通道并验证最终文件；签名需由调用方提供密钥和信任资料。
- 流式布局与可选磁盘索引 PDF 各有资源契约；普通 PDF 后端仍持有页树。有限容量验证不等于支持任意规模文档或无限期无泄漏，见[流式与重放](docs/streaming-replay-api.md)。
- 固定尺寸内容须放入预留区域；资源预算不能替代不可信输入隔离。

## 待完成与路线图

当前剩余工作由[能力缺口表](docs/capability-gaps.md)跟踪：

- **GAP-14.2：** 更广兼容语料及跨平台应用／阅读器验证。
- **GAP-14.3：** 稳定 API 与版本验收；`0.1.0-rc.2` 的签名制品已通过 Maven Central 分发。
- **GAP-22：** XLSX、PPTX、EPUB、XPS 等更多格式。

验收条件见[执行计划](docs/remaining-capabilities-plan.md)。OCR 不在当前范围内。

## 文档导航

先从[常见问题](docs/faq.zh-CN.md)确认 Java 环境、模块选择、字体、转换限制和许可证。[文档索引](docs/README.md)按使用任务组织详细指南。

| 任务 | 阅读入口 |
| --- | --- |
| 用 Java 生成 PDF 报告与发票 | [完整 Java 示例](#java-使用示例) · [编写指南](docs/authoring-api.md) · [表格与分页](docs/table-api.md) |
| 从 XML 模板生成报告 | [模板示例](#xml-模板) · [XML 指南](docs/xml-template-api.md) · [模板资源包](docs/xml-template-bundle-api.md) |
| 导出 Word 文档 | [DOCX 语义与编辑](docs/docx-semantic-api.md) · [页区](docs/docx-page-regions-api.md) |
| 配置中文与多语言字体 | [字体和文本](docs/text-api.md) · [字体来源与许可](assets/fonts/README.md) |
| 导出长表与大文档 | [流式长表](docs/streaming-table-api.md) · [逐页导出](docs/paged-document-api.md) · [重放与磁盘索引 PDF](docs/streaming-replay-api.md) |
| 导入、编辑与转换 | [统一转换](docs/document-conversion-api.md) · [DOCX 语义](docs/docx-semantic-api.md) · [HTML/CSS](docs/html-import-api.md) |
| 处理 PDF | [PDF 适配器](docs/pdf-adapter-api.md) · [恢复](docs/pdf-semantic-import-api.md) · [标准](docs/pdf-standards-api.md) |
| 查看示例与实现 | [示例](docs/examples.md) · [架构](docs/architecture.md) · [ADR](docs/adr/README.md) |

## 构建与验证

```powershell
.\gradlew.bat build sbom publishLocalBundle
powershell -NoProfile -File scripts/verify_local_consumer.ps1
```

Linux/macOS 使用 `bash ./gradlew`。本地制品写入 `build/repository`；独立调用方通过 Maven 坐标解析并实际生成文档。

[GitHub Actions](https://github.com/mirakyux/export4j/actions/workflows/build.yml)在推送 `main`、拉取请求及手动触发时执行，覆盖 Java 21 的 Windows／Linux／macOS 构建、API 检查、本地制品消费及临时密钥签名验证。应用／阅读器验收另行记录，完整流程见[开发规约](docs/development.md)和[发布验证](docs/releasing.md)。

## 参与贡献

欢迎缺陷报告、可复现示例、文档及范围明确的功能贡献。提交拉取请求前请阅读[贡献指南](CONTRIBUTING.md)。

缺陷与建议使用 [GitHub Issues](https://github.com/mirakyux/export4j/issues)。安全问题请通过[私密漏洞报告](https://github.com/mirakyux/export4j/security/advisories/new)提交，详见[安全政策](SECURITY.md)。

## 开源致谢

Export4j 复用了以下开源基础设施与显式提供的字体资源：

| 项目／资源 | 用途 |
| --- | --- |
| [Apache PDFBox／FontBox](https://pdfbox.apache.org/) | PDF 后端、受限 PDF 处理与字体解析 |
| [Apache Commons Logging](https://commons.apache.org/proper/commons-logging/) | PDFBox 传递运行依赖 |
| [commonmark-java](https://github.com/commonmark/commonmark-java)、[jsoup](https://jsoup.org/) | 离线 Markdown 与 HTML 解析 |
| [Bouncy Castle](https://www.bouncycastle.org/) | PDF 签名、证书、撤销及时间戳基础能力；后端内部依赖 |
| [LWJGL](https://www.lwjgl.org/)、[HarfBuzz](https://harfbuzz.github.io/) | 可选的原生 OpenType 塑形、轮廓及彩色字形绘制 |
| [OpenJDK](https://openjdk.org/) | Java 运行时、Java2D／OpenType、ZIP 与 XML 基础设施 |
| [Gradle](https://gradle.org/)、[JUnit](https://junit.org/) | 构建与 Java 测试 |
| [ManuWeave](https://github.com/jiassia/manuweave/) | [286 个学术模板示例](examples/src/main/resources/templates/manuweave/README.md)的目录及排版事实来源；数据采用 MIT 许可，示例明确记录字体替代与排版限制 |
| [pypdf](https://github.com/py-pdf/pypdf)、[pypdfium2](https://github.com/pypdfium2-team/pypdfium2)、[Pillow](https://python-pillow.org/)、[python-docx](https://github.com/python-openxml/python-docx)、[cryptography](https://cryptography.io/)、[Poppler](https://poppler.freedesktop.org/) | 独立格式、渲染与验收工具 |
| [Liberation Fonts](https://github.com/liberationfonts/liberation-fonts)、[霞鹜文楷 TC](https://github.com/lxgw/LxgwWenkaiTC)、[站酷小薇](https://github.com/googlefonts/zcool-xiaowei) | 示例与测试显式字体 |
| [Noto Fonts](https://github.com/notofonts/noto-fonts)、[Source Sans 3](https://github.com/adobe-fonts/source-sans) | 复杂脚本及 CFF 示例／测试字体 |
| [Roboto Flex](https://github.com/googlefonts/roboto-flex)、[color-fonts](https://github.com/googlefonts/color-fonts) | 可变／彩色字体验证夹具 |
| [resvg-py](https://github.com/baseplate-admin/resvg-py)、[resvg](https://github.com/linebender/resvg) | 验证阶段的独立 SVG 渲染，不进入 Java 运行制品 |

具体版本、哈希、来源和分发义务见[依赖记录](docs/dependencies.md)、[字体记录](assets/fonts/README.md)、[NOTICE](NOTICE)及[原始许可证](third-party/licenses)。

## 许可证

项目自研代码与文档采用 [Apache License 2.0](LICENSE)。第三方依赖、字体及其他资源保留各自许可证，不因随项目分发而改为 Apache-2.0。
