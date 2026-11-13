# 行内矢量资源

`InlineVector` 将调用方已经生成的有界轮廓放入段落、标题、两种列表、语义表格单元格及固定页区，与相邻文字一起换行和分页。PDF、SVG、PNG 使用同一布局结果。该入口不解析 TeX、SVG 或 MathML，也不引入公式运行库；TeX 公式可直接使用可选[公式模块](math-api.md)，无需使用端维护解析和轮廓捕获。

## 创建原子内容

```java
import java.util.List;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineVector;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.TableCell;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.VectorResource;

var triangle = VectorResource.of(18, 10, 2, List.of(
        new VectorResource.Segment(VectorResource.Operation.MOVE, List.of(0.0, 2.0)),
        new VectorResource.Segment(VectorResource.Operation.LINE, List.of(9.0, -10.0)),
        new VectorResource.Segment(VectorResource.Operation.LINE, List.of(18.0, 2.0)),
        new VectorResource.Segment(VectorResource.Operation.CLOSE, List.of())));
var formula = new InlineVector(triangle, "\\triangle", 0x223344, "");
var format = new ParagraphFormat(12, 24, 0, 0,
        ParagraphAlignment.LEFT, 0, 6, false);
var paragraph = Paragraph.mixed("formula", List.of(
        new InlineText("符号：", InlineStyle.of(12)), formula), format);
var cell = TableCell.mixed(paragraph.content(),
        new CellFormat(12, 24, ParagraphAlignment.LEFT));
```

轮廓坐标以 pt 为单位，相对基线，向下为正，所有控制点必须在 `[0, width] × [-ascent, descent]` 内。宽度大于 0，ascent／descent 非负且总和大于 0；宽度与总高度分别不超过 14,400 pt。使用非零绕组填充，颜色为 `0..0xffffff` 的明确 RGB；不支持描边、渐变、透明度或自定义填充规则。

`MOVE`／`LINE` 各含两个坐标，`CURVE` 含六个三次曲线坐标，`CLOSE` 不含坐标。每个子路径以 MOVE 开始，CLOSE 后须以 MOVE 开始新子路径。路径非空且最多 10,000 段；NaN、无穷值、越界坐标和非法操作顺序直接拒绝。

资源快照及段坐标均不可变，可跨模型复用，没有打开的句柄或隐式全局缓存。`id()` 是包含尺寸、段顺序及坐标的规范化 SHA-256，`-0.0` 与 `0.0` 等价。`byteSize()` 是版本化规范编码长度，不是实际堆占用。两参数 `new InlineVector(resource, logicalText)` 使用黑色且不带链接。

## 来源、编辑与预算

一个矢量在原始 `Paragraph.text()` 中只占一个 U+FFFC、一个 UTF-16 单元；`Paragraph.vectors()` 保存 `VectorSpan(sourceIndex, vector)`。`content()` 和 `TableCell` 保留对象顺序，图片、字段与矢量不能共用同一来源位置。每段最多 1,024 个矢量标记。手动构造时必须提供完整 inline runs，标记使用段落默认样式和矢量自身链接；优先用 `mixed(...)` 自动生成。标记无需字体提供 U+FFFC 字形。

`logicalText` 最多 1,024 个 UTF-16 单元，拒绝控制字符、非法代理项、U+FFFE／U+FFFF 和行／段分隔符。它描述已有轮廓，**模板不会对其中的 `${...}` 插值**；相邻普通文字替换后重新计算标记位置。动态公式须重建整个 `InlineVector`，可用 `DocumentEditor.vector(id, sourceIndex, value)` 整体替换，普通文字编辑不会吃掉原子对象。链接可单独绑定，并遵守 HTTP／HTTPS／mailto 被动外链限制。

行距必须容纳字体及所有矢量的 ascent／descent。过宽或过高时明确失败，不自动缩放或裁切。Auto 列宽的原子下限包含矢量宽度；重复表头、跨页和变宽区域继续按完整行处理。

普通布局对每次逻辑文字和呈现工作计费；同一布局输入的不同矢量资源合计上限为 5,000,000 段、64 MiB 规范字节。单次段落塑形还在转换前按每次出现累计矢量段数，超过 2,000,000 段立即拒绝；相同资源仅在本次转换中共享不可变路径，实际生成的文字和矢量轮廓仍受文字层总段数限制。流式表格保守地按每次出现计入全流几何预算，避免为全流保留无界资源 ID 集合。固定页区另限 1,000,000 段、16 MiB 规范字节，均按出现次数累计，且禁止链接。模板将逻辑文字计入 `maxCodePoints`，段数计入 `maxVisitedNodes`；编辑将逻辑文字计入 `maxTextUtf16`，规范字节按次计入 `maxWork`。这些是工作账本，不是 RSS 保证。

## HTML 与 Markdown 注册

```java
import com.mirakyux.export4j.adapter.web.HtmlImporter;
import com.mirakyux.export4j.adapter.web.MarkdownImporter;

var html = HtmlImporter.builder().paragraphFormat(format)
        .vector("formula-1", formula).build()
        .read("<p>符号：<img src=\"formula-1\" alt=\"triangle\"></p>")
        .execute().document();
var markdown = MarkdownImporter.builder().paragraphFormat(format)
        .vector("formula-1", formula).build()
        .read("符号：![triangle](formula-1)")
        .execute().document();
```

注册键精确匹配 HTML `img src` 或 Markdown 图片 destination；图片和矢量不能占用同一个键。不会读取键指向的文件、下载资源或把任意 `$...$` 当作公式。注册对象的 `logicalText` 是公式原文，`alt` 不替代它。HTML 显式宽高／CSS 尺寸必须等于资源的 pt 尺寸，冲突直接失败；需要缩放时由调用方重新生成整体资源。

段落、标题、两种列表及语义单元格均保留矢量，可分别使用 `Paragraph.mixed`、`Heading.mixed`、`BulletItem.mixed`、`NumberedItem.mixed` 和 `TableCell.mixed`。列表前缀只移动对象的 UTF-16 锚点，不展开公式或重建轮廓；列表 Builder 的 `content(...)` 立即快照输入，最终字号只决定内部对象占位样式，显式文字样式保持原值。自动标题书签使用普通文字、图片 alt 和矢量阅读文字；含动态字段的标题须使用明确书签标题。`MarkdownTemplate` 是另一个模板入口，本次不增加矢量语法。

`Document.Builder` 的混合内容便利入口为 `headingContent(content, level)`、`bulletContent(content)` 和 `numberedContent(listId, content)`。原有 `heading`／`bullet`／`numbered` 的 `List<InlineText>` 签名及行为保留。

## XML 保存与恢复

XML v1 支持静态的有界路径，可与文字、图片、字段混排，并通过 `XmlTemplateWriter` 保存为普通资源 ZIP：

```xml
<document version="1">
  <heading level="1" size="12" line-height="24">Symbol:
    <vector width="18" ascent="10" descent="2" logical="\triangle" color="#223344">
      <segment operation="MOVE" points="0 2"/>
      <segment operation="LINE" points="9 -10"/>
      <segment operation="LINE" points="18 2"/>
      <segment operation="CLOSE"/>
    </vector>
  </heading>
</document>
```

尺寸、坐标、操作顺序、颜色和段数使用同一模型校验。`logical` 始终作为字面阅读文字，`${...}` 不会单独插值后与旧轮廓错配；动态公式须重建整个资源或使用公式模块。路径无外部资源引用。编译、绑定、保存及 ZIP 各自保留有限输入、节点、文字及输出预算。标题／列表保存保留原对象顺序、外链和 UTF-16 位置；标题不接受脚注标记，两种列表接受有匹配注释的显式脚注标记。

## 输出与文字提取边界

| 目标 | 当前行为 |
| --- | --- |
| PDF | 绘制真实轮廓，以 `ActualText` 保存 `logicalText`；外链使用最终对象几何 |
| SVG／PNG | 绘制同一矢量；带交互链接时仍按静态后端契约拒绝，不静默删链接 |
| HTML／Markdown 导出 | STRICT 拒绝；明确 `INLINE_VECTOR`／DEGRADE 时输出转义后的逻辑文字，并报告轮廓／颜色／几何损失 |
| DOCX | 普通无损写出入口拒绝；统一转换明确 `INLINE_VECTOR`／DEGRADE 时输出逻辑文字并报告损失，不转成 OMML 或图片 |
| XML 模型保存／资源 ZIP | v1 静态路径编码完整保存几何、颜色、链接和阅读文字；非法路径或预算超限直接拒绝 |

`ActualText` 不是普通字体字形：支持该属性的阅读器可搜索原文，忽略它的提取器可能只看到轮廓的不可见锚点，不能承诺逐字坐标或任意阅读器选区。原 TeX 只作为逻辑阅读文字，不能据此声称可编辑公式、TeX↔OMML 转换或 PDF/UA 合规。

仅含图片的安全混排行可保留普通文字的原生 TTF 字形以改善常规提取：限从左到右、静态 TrueType、码点与实际 GID 一一对应、无装饰、无动态字段和无矢量对象的路径。仍复用已有塑形结果和坐标；任一条件不满足则整行保留原轮廓。复杂脚本、组合簇、双向文字、连字、CFF／可变／彩色字体及矢量公式附近文字不能据此推断已转为原生字体输出。详见[行内图片](inline-images-api.md)及[文献接入](literature-pdf-integration.md)。

## 验证入口

模型回归见 [InlineVectorTest](../export4j-model/src/test/java/com/mirakyux/export4j/model/InlineVectorTest.java)。[完整样例](../examples/src/main/java/com/mirakyux/export4j/examples/InlineVectorsExample.java)复用既有示例内的 `ManuWeaveMath` 将三种受限 TeX 公式转成资源，没有新增生产解析依赖。从仓库根目录运行：

```powershell
.\gradlew.bat :examples:inlineVectors --console=plain
python scripts/verify_inline_vectors.py
```

Linux／macOS 将 Wrapper 改为 `./gradlew`。`python` 应使用安装了 `scripts/requirements-verification.txt` 中锁定依赖的隔离环境，另需 Poppler。默认样例在 `examples/build/inline-vectors/`，独立报告在 `build/verification/inline-vectors/`。

本地样例 1 页、3 个资源、8 次呈现，已核对实际 PDF／SVG 路径、基线、原子标记、PDFium 原 TeX 提取／搜索／选区，以及 PDF 双引擎、SVG 和导出 PNG 的全页渲染；5 项负控制均拒绝。全部新页面已复查，详见[验收记录](verification/inline-vectors.md)。两篇真实文献的本次 17 页检查单独见[文献接入](literature-pdf-integration.md#本次真实文献)；其中 29 处公式仍为原 PNG，不能将该结果当成全部文献公式已矢量化。
