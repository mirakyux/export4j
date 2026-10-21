# 组件 API

当前组件首版面向 PDF，覆盖页面、页眉页脚、文字、RGB 图像、表格、矩形、椭圆、直线、水印、目录、书签、评论批注和基础可填写表单。根包名为 `com.mirakyux.export4j`，长度统一使用 point，页面原点位于左上角。

新编写代码可使用 `Documents.builder()`、组件 Builder 和同步嵌套回调，见[链式 API](fluent-api.md)。本文中的显式构造示例继续兼容。

静态密集表单使用 `FormText`、`ChoiceGroup`、`CheckMark`、`LabelValue` 和 `FillLine`，见[静态表单指南](static-forms-api.md)。定位 `TableComponent` 仅支持单视口静态子集，明确拒绝字段、文字／图片外链及具名字体角色。组件容器内的这类内容使用 `SemanticTable`／`.semanticTable(Table)`，长表可在区域流中续排，由最终文档解析字段和交互，见[语义组合指南](semantic-components-api.md)。

文字组件 Builder 保留未设置状态，可从 `Documents.bodyFormat`／`StyleScope` 继承完整样式和颜色。显式设置优先；直接构造器仍固定所传值。网格拆行与浮动绕排在测量前解析，作用域保留子组件宽度，见[默认样式指南](style-defaults-api.md)。

## 模块与入口

调用方依赖 `export4j-components`，输出 PDF 时另选 `export4j-backend-pdf`。组件模块不依赖 PDF 后端，布局核心不依赖组件实现。

| 类型 | 职责 |
| --- | --- |
| `Component` | 核心 SPI，测量并返回局部不可变绘制与诊断 |
| `ComponentContext` | 视口宽高、显式字体栈及页上下文 |
| `ComponentLayout` | 实测宽高、文字行数、独立平衡指令和诊断快照 |
| `ComponentBlock` / `PageableComponent` | 普通整块流动；可分页 SPI 提供有序片段 |
| `Placement` / `Position` | 定位组件和视口解析 |
| `PageBand` / `PageTemplate` | 页眉页脚预留区域及背景／前景 |
| `PageComposer` | 最终页面合成，可返回定位轨迹和诊断 |
| `Navigation` / `TableOfContents` | 未解析的书签、目录、评论和字段 |
| `ComponentDocumentRenderer` | 正文分页、目录前置、最终目标解析及页区合成 |
| `DocumentExtras` | 供后端消费的已解析交互信息 |

## 最小编排示例

以下方法接收调用方显式加载的字体栈。字体加载方式见[文本 API](text-api.md)，可直接运行的完整示例见 [ComponentPdfExample](../examples/src/main/java/com/mirakyux/export4j/examples/ComponentPdfExample.java)。

~~~java
import java.util.List;
import com.mirakyux.export4j.components.BoxComponent;
import com.mirakyux.export4j.components.ComponentDocumentLayout;
import com.mirakyux.export4j.components.ComponentDocumentRenderer;
import com.mirakyux.export4j.components.Navigation;
import com.mirakyux.export4j.components.PageNumberComponent;
import com.mirakyux.export4j.components.TableOfContents;
import com.mirakyux.export4j.components.TextComponent;
import com.mirakyux.export4j.font.FontFace;
import com.mirakyux.export4j.graphics.Rect;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.layout.core.PageBand;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.layout.core.Placement;
import com.mirakyux.export4j.layout.core.Position;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.text.ParagraphStyle;

public final class ComponentExample {
    private ComponentExample() {
    }

    public static ComponentDocumentLayout compose(List<FontFace> fonts) {
        var document = new Document(new DocumentMetadata("组件示例", "Export4j"),
                PageSettings.A4, List.of(
                        new Paragraph("intro", "文档内容", ParagraphFormat.of(18)),
                        new ComponentBlock("form", new BoxComponent(300, 80,
                                new TextComponent("Name:", ParagraphStyle.of(12), RgbColor.BLACK)), 8, 8)));
        var footer = new PageBand(20, 8, List.of(new Placement(
                new PageNumberComponent("{page} / {pages}", ParagraphStyle.of(10), RgbColor.BLACK),
                new Position.Relative(Position.Anchor.BOTTOM_RIGHT, 80, 18, 0, 0))));
        var template = new PageTemplate(PageBand.EMPTY, footer, List.of(), List.of());
        var navigation = new Navigation(
                List.of(new Navigation.Bookmark("正文", "intro", 1)),
                new TableOfContents(true, "目录", ParagraphStyle.of(12)), List.of(),
                List.of(new Navigation.TextField(
                        new Navigation.BlockTarget("form", new Rect(60, 28, 200, 24)),
                        "name", "Ada", false)));
        return new ComponentDocumentRenderer().layout(document, template, navigation, fonts);
    }
}
~~~

将 `result.display()` 交给 `PdfBackend.write` 即可输出。布局阶段没有文件 I/O；写文件时由调用方创建并关闭输出流。

~~~powershell
.\gradlew.bat :examples:componentPdf
~~~

输出 `examples/build/export4j-components-example.pdf`。支持自定义路径：

~~~powershell
.\gradlew.bat :examples:componentPdf --args="examples/build/custom-components.pdf"
~~~

## 标准组件

| 组件 | 当前行为 |
| --- | --- |
| `TextComponent` | 视口内换行，使用实测高度，保存字体回退诊断 |
| `BoxComponent` | 为子组件预留固定宽高，子组件左上对齐 |
| `ImageComponent` | 显式 `RgbImage`；CONTAIN 等比完整显示、COVER 等比裁切、STRETCH 拉伸 |
| `TableComponent` | 在一个视口中测量表格，不能跨页；需要跨页时直接使用语义 `Table` |
| `SemanticTable` | 正文区域流中的语义表格，支持逐区表头和显式完整行拆分，保留字段、外链、字体角色及单元格来源；固定调用仍要求单片段 |
| `ShapeComponent` | 矩形与三次贝塞尔椭圆，不透明填充，可选内描边 |
| `LineComponent` | 起止点为视口内 0～1 比例，平直端点，描边保留在视口内 |
| `WatermarkComponent` | 淡色 RGB 文字、中心旋转、显式裁剪；没有透明度参数 |
| `PageNumberComponent` | 替换 `{page}` 和 `{pages}`，仅在最终页数确定后使用 |

图片组件消费显式 `RgbImage`，可由绘制核心 `ImageDecoder` 从有界 PNG／JPEG 字节解码并保留 alpha；不访问文件或 URL。`SvgComponent` 支持离线矢量子集。资源始终由调用方提供，不引入隐式下载或系统字体扫描。

## 四种布局方式

| 方式 | 示例 | 参照对象 |
| --- | --- | --- |
| 绝对坐标 | `new Position.Absolute(new Rect(48, 90, 200, 80))` | 当前页面坐标 |
| 锚点相对 | `new Position.Relative(Position.Anchor.BOTTOM_RIGHT, 200, 80, -10, -10)` | 父区域同名锚点，再叠加 point 偏移 |
| 比例坐标 | `new Position.Fractional(0.1, 0.2, 0.8, 0.3)` | 父区域宽高 |
| 正文流动 | `new ComponentBlock("image", component, 8, 8)` | 按文档顺序整块或按显式片段换页 |

九个锚点覆盖左／中／右和上／中／下。绝对坐标也必须落在该 placement 的父区域内；页眉中的绝对坐标依然是页面坐标，不是页眉局部偏移。

正文组件在完整内容区域内测量一次，放不下当前页时移动到下一页；超出新页内容区时明确失败。`BoxComponent` 可限制图片、形状或自定义组合的尺寸。正文段落和表格仍通过原语义节点进行完整行／整行分页。

上述整块规则适用于普通 `Component`。`FlowText`、`FlowColumn`、`GridTable` 与 `SemanticTable` 实现分页 SPI，可以返回有序续页片段，首片段使用当前页剩余高度；固定区域中仍要求只返回一个片段。`RowComponent` 支持等宽排列但整行不可拆页。具体约束见[进阶 API](advanced-api.md#可跨页组件与合并表格)。

`ContinuousColumns` 通过核心 `RegionFlow` 从剩余正文区域开始多栏，再使用完整续栏，最后按实际高度接续正文。支持文字、`FlowColumn` 混合流和 `GridTable` 表格；其他组件可通过混合流按整块／原有分页片段参与，详见[连续分栏 API](continuous-columns-api.md)和[混合分栏 API](mixed-columns-api.md)。

正文段落通过 `InlineField` 提供当前页码、总页数、目标页码／编号和行内注释标记。字段先按固定宽度参与断行，再由编排器解析最终值；脚注跟随标记实际所在行。组件内部、表格单元格和连续栏内字段不在本批范围，见[字段 API](inline-fields-api.md)。

`ComponentContext.pageCount() == 0` 表示分页前测量，`pageIndex()` 只是测量时的候选页。正文组件必须独立于页索引及最终页数；移动到下一页不会再次测量。最终页字段在页区合成阶段获得真实从 0 开始的页索引和总页数，显示数字从 1 开始。

## 页面区域与图层

`PageSettings` 定义统一页面大小和外边距。页眉从上边距向下占据 `height`，页脚从下边距向上占据 `height`；各自 `gap` 隔开正文。正文原上／下边距分别增加对应页区高度及间隔。

编排入口自动先预留区域再分页；直接调用核心时，先使用 `template.bodySettings(settings)` 排版正文，再把原始 `settings` 传给 `PageComposer`，不能重复预留。

页面绘制顺序为背景、正文、页眉页脚、前景。背景和前景参照完整页面，页眉页脚参照各自区域。单节入口使用统一模板；分节入口的 `SectionTemplate` 支持首页／奇偶模板，提前预留最大页区。目录页使用第一节的纸张和奇偶模板，正文各节可以更改纸张并重置显示页码，见[进阶 API](advanced-api.md#分节与显示页码)。

`PageComposition.Placed` 保留最终页索引、区域、区域内 placement 索引、解析后视口与 `ComponentLayout`。编排结果同时保存正文、目录及装饰诊断，不丢弃字体回退信息。

视口必须位于父区域内，实测尺寸不能超过视口。自定义组件可生成任意指令，合成器不会自动裁剪其内容；需要限制绘制时实现者显式发出 Clip。组件是可信 Java 代码，预算不是执行沙箱。

## 目录、书签与目标

`Navigation.Bookmark(title, blockId, level)` 指向原始正文块。层级从 1 开始，下一项不能跳过中间层；定位到对应块的第一个片段。

目录由书签顺序生成，作为独立前置页面。先测量标题与条目，预留 48 pt 页码列，再计算目录页数及正文偏移，最后输出真实页数字和内部链接。条目可换行但不能拆页，目录标题在续页重复。没有书签、条目超高或页码列不足时明确失败。

`ComponentDocumentLayout.body()` 的页索引属于正文局部坐标。最终页索引等于正文页索引加 `bodyPageOffset()`；`display()` 及 `decorations()` 已使用最终坐标。

| 目标类型 | 解析规则 |
| --- | --- |
| `BlockTarget(blockId, relativeBounds)` | 跟随正文首片段，矩形偏移以片段左上角为参照 |
| `PageTarget(pageIndex, position)` | 指定最终页面，包含目录页；position 参照完整页面 |

目标矩形必须在页面内。块目标不会自动为批注或字段腾出正文空间，也不要求矩形位于片段内部；调用方应预留填写区，避免覆盖其他内容。未知块、无效页、越界矩形和重复字段名均失败。

## 评论与表单

评论输出为 PDF Text annotation，保存作者、内容和图标外观；不将评论写成正文。书签和目录跳转采用真实页对象及 XYZ 目标，不使用脚本。

AcroForm 包含可编辑单行／多行文本框、复选框、封闭下拉框与单选组。平面字段名匹配 `[A-Za-z_][A-Za-z0-9_-]{0,127}`，全文档唯一。文本字段空字体列表仅允许可打印 ASCII，多行可含 LF；传入一个显式 `FontData` 时支持中文初始值和后续编辑。

表单默认使用标准 Helvetica 10 pt，显式字体则完整嵌入；保存 `/V`、`/DV` 和 `/AP`。复选框保存 `/Off`、`/Yes` 及当前 `/AS`；单选组使用一个规范父字段和多个子 widget，选项导出名保持 ASCII。字段树与页面 widget 保持一致，`NeedAppearances=false`，不扁平化。过长文字可能需要阅读器滚动显示，不承诺全部值完整显示在可见框内。初始值缺字保持失败，不应用正文占位符策略。完整示例见[进阶 API](advanced-api.md#中文表单下拉框与单选组)。

PDF 阅读器对填写、批注面板与保存的支持不同。本次验证包含独立填写、保存、重开与渲染；不代表所有浏览器和阅读器已验证。

## 预算、诊断与兼容性

`DocumentLayoutLimits` 继续约束正文块、原文码点、文字行、表格结构及页面。正文组件生成的 GlyphText 字形码点和 Text 码点加入同一文本预算，组件报告的行数加入行预算；这些统计发生在自定义组件返回之后。

目录标题与条目共用一份独立的 `maxCodePoints` 预算，在测量条目前检查；目录输出行数使用独立的 `maxLines` 预算，计入重复标题与页码行。正文和目录共享总页数及最终绘制命令预算。

`ComponentLimits.DEFAULT` 为 10,000 次页区 placement、1,000,000 条最终命令、10,000 项交互。页区 placement 按页面重复次数计数；最终命令包含正文、目录及合成的 Save／Transform／Restore。正文布局也单独检查组件命令总数。交互计数包含书签、目录链接、评论和字段。总页数包含目录。PDF 后端另有独立的文字、图像、路径、字体和交互预算，见[绘制 API](graphics-api.md)。

正文组件非法结果或参数失败包装为 `COMPONENT_LAYOUT_FAILED`，保留块、页及原因；不识别的 Block 返回 `UNSUPPORTED_COMPONENT`。页区、目录和目标解析的参数／预算问题使用 `IllegalArgumentException`，底层文本异常保留原类型。自定义组件的其他运行时异常原样传播，不伪装成成功。

这次公开契约变化如下：

- `Block` 从 sealed 改为开放接口，第三方通过 `ComponentBlock` 适配布局；原有 exhaustive switch 需检查默认分支。
- `LayoutFragment` 新增 `ComponentFragment`，完整遍历继续使用 `elements()`。
- `DisplayDocument` 新增 `extras`，保留原两参数构造器。
- `PdfLimits` 新增交互数量和文字预算，保留原六／七参数构造器。
- `DocumentLayouter` 新增包含 `ComponentLimits` 的三参数构造器，原构造器继续有效。

项目尚未正式发布；record 组件数量、反射序列化和枚举／sealed exhaustive switch 的调用方仍须重新核对。

进阶能力已补齐分页约束、保持相邻、分节、纵向嵌套流、富文本、合并表格、中文表单和下拉框，见[进阶 API](advanced-api.md)。后续已实现矩形绕排、多栏、公共复杂文本和外部 CMS 签名入口，见[能力指南](capabilities-api.md)。透明水印与脚本动作尚未支持。初始架构见 [ADR-0006](adr/0006-components-and-pdf-interactions.md)，扩展见 [ADR-0007](adr/0007-advanced-layout-and-font-policy.md)，证据见[进阶验收](verification/phase-3-advanced.md)。
