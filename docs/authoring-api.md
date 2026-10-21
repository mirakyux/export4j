# 常用编写 API 与中文排版

普通报告可以通过 `Document.Builder` 编写，再用 `DocumentEngine.write` 输出。复杂分节、页区、目录、脚注及自定义组件保留显式入口。完整可运行示例见 [`AuthoringExample`](../examples/src/main/java/com/mirakyux/export4j/examples/AuthoringExample.java)。

页面、样式、组件、合并表格和后端的具名 Builder，以及可见的嵌套结构，见[链式 API](fluent-api.md)。密集表单已使用新入口重写，旧短入口继续兼容。

## 最短调用

以下代码使用项目 API 和 JDK。字体文件由调用方提供；库不扫描系统字体，也不携带示例字体。

~~~java
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.Document;

// 在可抛出 IOException 的调用方法中：
var latin = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("fonts/latin.ttf")));
var chinese = TrueTypeFontLoader.load(Files.readAllBytes(Path.of("fonts/chinese.ttf")));
var engine = DocumentEngine.builder(FontGroup.strict(List.of(latin, chinese))).build();
var document = Document.builder()
        .heading("季度报告", 1)
        .paragraph("这里是正文。")
        .bullet("关键结论")
        .numbered("steps", "准备数据")
        .numbered("steps", "导出报告")
        .table(List.of("指标", "数值"), List.of(List.of("收入", "120")))
        .build();
var result = engine.write(document, new PdfBackend(), Path.of("report.pdf"));
~~~

`Document.builder()` 默认使用 A4、四边 54 pt 页边距、空元数据和 12 pt 正文。正文固定行距为 18 pt，段后间距为 6 pt。标题按级别设置字号，并保持整段与下一块。`metadata(...)`、`pageSettings(...)` 修改文档设置；`bodyFormat(...)` 只影响随后添加的正文、列表和便利表格。正文样式不覆盖标题默认格式。

默认行距不适合任意字体。阿拉伯语等高行框字体必须配置足够的固定行距；可用 `ParagraphLayouter.minimumLineHeight(group, size)` 测量最低要求。字号、行距和图片高度仍由调用方控制，引擎不会自动缩小或裁切内容。

简单表格使用等比例列宽、一个重复表头和默认内边距，要求至少一个正文行，且每行列数相同。自动列宽、富文本单元格、拆行续页等使用完整 `Table` API，见[表格指南](table-api.md)。

## ID 与命名

ID 是内容块在当前文档内的唯一标识，不显示在正文。它用于书签、页码引用、编号引用、注释绑定和诊断定位。

普通入口生成 `auto-1`、`auto-2` 等 ID，并跳过此前显式添加的同名块。相同调用序列产生相同 ID，但插入或删除内容后序号可能改变。需要作为引用目标的内容应显式命名：

~~~java
var document = Document.builder()
        .heading("overview", "概览", 1)
        .paragraph("summary", "汇总正文")
        .pageBreak()
        .paragraph("下一页正文")
        .build();
~~~

`numbered("steps", ...)` 中的 `steps` 是列表组 ID，用于继续同一列表的计数；每个列表项仍有独立的块 ID。嵌套或重启编号使用 `NumberedItem`。显式命名与自动 ID 冲突时，`build()` 明确失败。构建结果是不可变快照，Builder 只应在单个任务内使用。

## 两端对齐与严格中文禁则

~~~java
var format = ParagraphFormat.of(12)
        .withAlignment(ParagraphAlignment.JUSTIFY)
        .withLineBreakPolicy(LineBreakPolicy.CJK_STRICT);
var document = Document.builder().bodyFormat(format).paragraph("需要排版的正文……").build();
~~~

`JUSTIFY` 仅拉伸软换行后的非末行。优先在行内 ASCII 空格分配剩余宽度；没有空格时，在合法的相邻中日韩文字边界分配。末行、显式换行结束的行和没有可扩展间隙的行保留自然宽度。缩进后的剩余宽度是对齐目标。字形本身不缩放，图像和字段预留宽度不改变，UTF-16 来源与分页保持一致。极窄栏中的少量空隙可能较大；目前没有自动限制最大伸展或重新优化整段断行。

`STANDARD` 保留旧默认行为。`CJK_STRICT` 在普通边界和紧急换行中检查原文，禁止指定开标点位于行尾、闭标点位于行首，保留成对省略号／破折号及 NBSP、窄不换行空格、word joiner 两侧的组合。显式换行仍由调用方决定。无法放入完整组合时明确失败，不溢出、不反复移页。

这些规则是有限中文排版子集，不代表完整 UAX 14、出版社规则或 Word 排版兼容。缺字替换不会取消原文禁则；原码点和 UTF-16 警告保持可追溯。`CellFormat`、`ParagraphStyle` 具有对应的 `withAlignment`、`withLineBreakPolicy`，语义表格、`FlowText`、`OpenTypeText` 可使用相同策略。

## 富文本标题和列表

`Heading.rich`、`BulletItem.rich`、`NumberedItem.rich` 接受 `List<InlineText>`，保留字体角色、字号、颜色、基线偏移、下划线、删除线和外部链接。列表生成的编号／项目符号使用段落默认样式，链接仅覆盖对应内容。

~~~java
var runs = List.of(new InlineText("重点", new InlineStyle("bold", 12, 0x285080, 0, true, false)));
var document = Document.builder()
        .heading(runs, 1)
        .bullet(runs)
        .numbered("steps", runs)
        .build();
~~~

字体角色必须通过 `DocumentEngine.Builder.fontRole` 显式注册。真实粗体需要真实字体变体，不合成假粗体。需要显式 ID、格式或层级时，使用富文本静态工厂再 `add(...)`。标题与列表当前支持文本和外链；图片、字段继续使用正文或单元格入口。

模板绑定保持富文本运行，导航从标题逻辑文字生成名称。DOCX 保留上述语义块及运行，并将两端对齐映射为标准 `w:jc="both"`。严格禁则使用 Word 的禁则提示及可忽略的自有策略标记往返；Word 自行排版，可能移除标记。没有引擎标记的外部启用禁则不能推测为同一规则，严格导入拒绝，允许损失时返回诊断。

## 输出与兼容性

`write(document, backend, Path)` 使用同目录临时文件与原子替换。父目录必须存在；失败或取消保留旧文件，并清理临时文件。它要求文件系统支持原子移动。`write(..., OutputStream)` 借用调用方流，不关闭流；失败可能留下部分内容。两种入口均提供 `ExecutionOptions` 重载。默认配置没有超时与输出字节上限，服务端应显式设置预算。

`SectionedDocument` 也支持简短的 `layout`、流输出和路径输出，默认每节使用空页区和空导航。目录、书签、页区、注释等使用已有完整 `layout(document, templates, navigation, notes)`，再向选定后端写出结果。引擎入口不依赖具体 PDF 后端。

旧构造器继续可用，新增 record 成员默认为旧策略。调用方的穷尽 `switch` 需要处理新增 `JUSTIFY` 枚举值。项目尚未发布；新增 record 成员要求重新编译调用方，本阶段不承诺二进制兼容。

当前 API 的评价是：普通报告已有短入口，精确控制仍需要理解语义、布局和输出边界。复杂组件组合的参数较多，仍属于进阶 API。验证证据和剩余范围见[阶段十三验收](verification/phase-13-authoring.md)。

多个报告可在编写入口应用[DocumentTheme](themes-api.md)，统一正文、六级标题和语义表格默认值；专用段落使用自动 ID 的完整格式重载，命名行内样式保持完整字号和角色。全部值在内容加入时解析，见[阶段三十验收](verification/phase-30-authoring-themes.md)。

列表标记现可通过 `NumberedItem.builder()`／`BulletItem.builder()` 单独设置完整 `markerStyle`，正文首个富文本运行不改变标记。旧默认与构造入口保持；新增 record 分量的形状变化、显式恢复及 DOCX 边界见[列表标记指南](list-markers-api.md)。
