# 可选公式 API

`export4j-math` 提供受限 TeX 转换和 Markdown 公式识别。使用端不用维护公式正则、Java2D 轮廓捕获或 TeX→OMML 转换器。该模块按需引入，核心模型、普通 Markdown 导入和输出后端不依赖公式库。

此功能属于当前开发版，未包含在已发布的 `0.1.0-rc.3` 中。需要同一源内容同时输出 PDF 与原生 Word 公式、混合 HTML 表格及逐目标降级时，使用[混合内容与跨格式公式](rich-content-api.md)的 `MathContent` 入口。

## 引入与直接转换

使用与其余 Export4j 模块相同的开发版本，通过 BOM 对齐：

```kotlin
dependencies {
    implementation(platform("com.mirakyux.export4j:export4j-bom:0.1.0-SNAPSHOT"))
    implementation("com.mirakyux.export4j:export4j-math")
}
```

```java
import com.mirakyux.export4j.math.MathOptions;
import com.mirakyux.export4j.math.TexMath;

var options = MathOptions.builder().fontSize(14).display(true).build();
var vector = TexMath.vector("\\frac{a+b}{c}", options);
var nativeMath = TexMath.docx("\\frac{a+b}{c}", options);
```

`vector` 返回现有 `InlineVector`，可用于 `Paragraph.mixed`、`Heading.mixed`、`NumberedItem.mixed`、`BulletItem.mixed`、`TableCell.mixed` 和显式 HTML 图片资源登记。PDF／SVG／PNG 继续由共享布局与后端绘制真实曲线；不启动 TeX、浏览器、Office 或打印服务。公式是不可拆的原子内容，此直接转换入口不自动缩放到行宽。调用方显式提供正文字体，并预留足够的固定行高。

矢量坐标与字号单位是 pt，轮廓相对基线。`logicalText` 保存 TeX，仅将换行、回车和制表符替换为空格，以满足行内阅读文字契约；原 Markdown 源码范围由 `FormulaSpan` 保留。PDF ActualText、搜索和逐字选区是不同能力，不能把阅读文字当成逐个 TeX 字符的轮廓坐标。

`docx` 返回既有 `DocxMath` 类型树，可绑定到 `DocxMathDocument` 后交给 `DocxAdapter.writeMath`。原生公式字号继承宿主段落，`fontSize` 只控制矢量排版。原生树不自动参与 PDF 排版。

## Markdown 一步导入

```java
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.math.MarkdownMath;
import com.mirakyux.export4j.model.ParagraphFormat;

String markdown = "The result is $x^2 + y^2 = r^2$.";
var math = MarkdownMath.builder().options(options).build();
var pdfInput = math.vector(markdown, importer -> importer.paragraphFormat(
        ParagraphFormat.builder().fontSize(12).lineHeight(48).build()));
// pdfInput.document() can be laid out with DocumentEngine and explicit fonts.

var wordInput = math.docx(markdown);
// output is a caller-owned OutputStream.
new DocxAdapter().writeMath(wordInput.document(), output);
```

每次调用创建新的 Markdown 导入 Builder，并同步执行回调。回调可设置原有导入策略、正文格式、`cellFormat` 单元格格式、页面和显式资源；库不保留它。结果分别返回文档、普通 Markdown 的 `importReport`、公式 `degradations` 和原文 `formulas`。两类诊断都需要处理，公式策略不放宽普通导入的损失规则。

单个 `$...$` 限同一行，内侧首尾不可为空白，结束符后不可紧接数字。独占一行的 `$$` 可包围多行展示公式。代码块、行内代码、链接及图片整体、引用定义、HTML 标签和 HTML 块不参与识别；反斜线按奇偶数处理。此规则减少 `$5 and $10` 一类货币歧义，不能推断作者意图。未配对分隔符保留为普通 Markdown；`scan(source)` 可只获取识别结果。

`FormulaSpan.start/end` 是包含分隔符的原 Markdown UTF-16 半开区间；`tex` 去除边界空白。导入后的每个公式仍只占一个 U+FFFC。相同 TeX 的多次出现独立绑定，标题、有序／无序列表、正文及表格中的公式使用真实段落和 UTF-16 锚点，不以公式文字作为唯一标识。标题／列表中的图片、矢量与公式均由混合行内模型保留；表头公式使用真实物理单元格锚点。未支持的源结构继续明确失败。

## 支持子集

| 语法 | 矢量 | Word 原生 |
| --- | --- | --- |
| 普通字母、数字、常用运算符、希腊字母 | 已声明白名单；ASCII 源和显式命令 | 已声明白名单；保留合法 Unicode |
| `\frac`、`\sqrt`、可选根次数、上下标 | 参数组有界；分式／根式要求花括号 | 支持有界语义转换 |
| `\text`／`\textrm` | 默认使用固定字体的 ASCII 子集；显式 `MathTextFonts` 可提供受限 Unicode 文本轮廓 | 支持 |
| `\mathrm`／`\mathit` | 显式 Computer Modern 字体覆盖的 ASCII 子集 | 支持；另有 `\mathbf`／`\textbf`／`\textit` |
| `\bar`／`\overline` | 支持有界上划线 | 明确拒绝；可由 `MathContent` 显式降级 |
| `\sum`／`\int`、`\left`／`\right` | 支持受限排版 | 明确拒绝，避免猜测运算对象范围 |
| `matrix`／`pmatrix`／`bmatrix`／`cases` | 至多 64 格，要求矩形；cases 两列 | 明确拒绝 |
| 宏定义、外部文件、URL、任意尺寸／字体命令 | 拒绝 | 拒绝 |

两种入口的子集分别定义，不能认为矢量可绘制就一定能转换为可编辑 OMML。不提供一般 LaTeX、TeX↔OMML 双向转换、MathML 或公式 OCR。

公式中的中文单位等文字使用显式字体，而不是系统字体回退：

```java
import com.mirakyux.export4j.math.MathTextFonts;

var textFonts = MathTextFonts.of(explicitStaticFontBytes);
var withUnit = TexMath.vector("24\\text{岁}", options, textFonts);
// MathContent.builder().textFonts(textFonts) uses the same explicit text-font configuration.
```

`MathTextFonts` 仅扩展 `\text`／`\textrm` 内的文本：支持 BMP 范围内从左到右的拉丁、汉字、假名、韩文、希腊、西里尔及公共字符；拒绝组合字符、需要复杂塑形的文字、补充平面字符及控制字符。每个字符按配置顺序选择明确覆盖它的字体，缺字失败；不扩展普通 TeX 数学语法。配置最多 8 个静态独立 TrueType／OpenType CFF 字体，每个 32 MiB、总计 64 MiB；拒绝 TTC、可变字体及受限、仅位图或禁止子集化的嵌入权限。调用方仍须具有字体使用许可。

## 失败、预算与取消

默认严格失败。需要显式保留不支持公式的源码时：

```java
var lenient = MarkdownMath.builder()
        .fallback(MarkdownMath.Fallback.KEEP_SOURCE)
        .build();
var result = lenient.docx("Unsupported native syntax: $\\sum_{i=1}^n i$.");
// result.degradations() contains the occurrence range and reason.
```

降级将完整公式作为转义后的普通文字保留，不重新解释为 Markdown。`MathException.reason()` 区分非法语法、未支持语法、预算及渲染失败，消息不包含正文。渲染异常的 cause 保留用于诊断，应用不得无审查地对外输出底层堆栈。预算超限与 `ExecutionException` 取消／超时始终传播，不能用降级绕过。

| 单式预算 | 默认及不可提高的硬上限 |
| --- | --- |
| 单式源码 | 1,024 UTF-16 单元 |
| 参数递归／命令 | 16 层／256 个 |
| 捕获路径／捕获及最终段数 | 2,048 条／10,000 段 |
| 公式最大宽高／字号 | 1,024 pt／6～72 pt |

`MathOptions` 可收紧上述单式预算，不能突破硬上限。文档累计预算与单式硬上限分别配置：

| 入口 | 默认累计预算 | 可调整入口 |
| --- | --- | --- |
| `MarkdownMath` | 输入 1,048,576 UTF-16 单元、256 式、400,000 矢量段 | `maxInputLength`、`maxFormulas`、`maxTotalSegments`，均可声明更大的正整数 |
| `MathContent` | `ImportLimits.DEFAULT` 的源输入／语法树／工作量、1,000 式、1,000,000 矢量段 | `limits`、`maxFormulas`、`maxVectorSegments`，仍执行各层有限额度 |

提高公式总数不会放宽单式源码、深度或轮廓限制。`MarkdownMath` 的导入回调还需配置相应 `MarkdownImporter.limits(...)`，不能只提高扫描额度而忽略后续输入／输出预算。Word 仍须符合既有 `DocxMathDocument`、`DocxMathLimits` 和包预算。使用 `ExecutionContext.execute(executionOptions, operation)` 包围整个调用，检查点覆盖扫描、参数解析、等待渲染锁及轮廓处理。第三方解析和 Java2D 操作在有界输入内同步执行，取消在其返回后的检查点生效，不是强制抢占沙箱。

## 并发、资源与分发

矢量转换使用库内锁协调 JLaTeXMath 的共享状态，只保留五款固定字体的有限缓存，不缓存公式或文档。与直接调用 JLaTeXMath 的其他代码共享 JVM 时，不允许修改其全局命令、字体、DPI、字号或缩放；已知覆盖被拒绝，库不能隔离外部代码对上游全局状态的任意并发修改。

`MathTextFonts.of` 保留字体字节的私有快照，生命周期与该配置及持有它的编译器相同；构造校验不进入共享字体缓存。需要文本轮廓的单次转换在局部解析物理字体，不注册系统字体或保留跨文档解析缓存。释放配置及编译器引用后才可回收这些字节；它不是自动过期缓存。

JLaTeXMath 1.0.7 为可选模块的私有运行依赖，许可是 **GPL-2.0-or-later 加上游原始 linking exception**，不是 LGPL 或标准 Classpath exception。原许可及字体条款保留；数学 JAR 包含固定上游 sources JAR 和原始 Maven 构建 POM，详见[依赖记录](dependencies.md)。不修改、重打包或遮蔽上游同名类。

## 验证

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:math
python scripts/verify_math.py
```

Linux／macOS 使用 `./gradlew`。独立验证使用项目锁定的 Python 环境和 Poppler；完整命令、制品消费及实际执行范围见[公式验收](verification/math.md)。
