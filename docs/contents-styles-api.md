# 目录标题、条目与页码样式

`TableOfContents.style` 继续控制目录条目的完整排版。新增 `appearance` 独立配置目录标题和页码标签的完整排版，以及标题、条目、页码三处颜色。它不改变正文格式、书签文字、最终目标解析或固定 48 pt 页码列。

需要保存并恢复目录时，使用[模型声明 DocumentNavigation](portable-navigation-api.md)。它保留同样的排版／颜色意图并由默认引擎消费；本文运行时 API 继续适用于调用方自行管理的导航配置。

## 链式配置

~~~java
var contents = TableOfContents.builder()
        .title("报告目录")
        .style(new ParagraphStyle(11, 20, 0, 0, TextAlignment.LEFT))
        .appearance(style -> style
                .titleStyle(new ParagraphStyle(22, 36, 0, 0, TextAlignment.CENTER))
                .pageLabelStyle(new ParagraphStyle(14, 26, 0, 0, TextAlignment.RIGHT))
                .titleColor(new RgbColor(34 / 255.0, 68 / 255.0, 136 / 255.0))
                .entryColor(new RgbColor(0, .4, .2))
                .pageLabelColor(new RgbColor(.8, .1, .1)))
        .build();
var navigation = Navigation.fromHeadings(document, contents, List.of(), List.of());
var engine = DocumentEngine.builder(fonts).navigation(navigation).build();
engine.export(document).backend(new PdfBackend()).to(Path.of("report.pdf"));
~~~

也可先构建可复用的 `ContentsStyle`，再传给 `appearance(value)`。同步回调从当前声明的独立副本开始，回调结束即保存不可变快照；后续修改被保留的子 Builder 不影响已配置目录。长度和字号单位均为 point，颜色使用不透明 sRGB，字体仍由 `DocumentEngine` 的显式字体组提供。

## 默认值与声明意图

| 属性 | 未声明时 | 显式配置 |
| --- | --- | --- |
| 标题排版 | 当前条目字号和行距各乘 1.5，零缩进、左对齐、STANDARD 断行 | `titleStyle(ParagraphStyle)` |
| 页码排版 | 当前条目字号和行距，零缩进、右对齐、STANDARD 断行 | `pageLabelStyle(ParagraphStyle)` |
| 标题颜色 | 黑色 | `titleColor(RgbColor)` |
| 条目颜色 | 黑色 | `entryColor(RgbColor)` |
| 页码颜色 | 黑色 | `pageLabelColor(RgbColor)` |

`ContentsStyle.DEFAULT` 没有显式声明。`ContentsStyle.titleStyle()` 和 `pageLabelStyle()` 返回 `Optional<ParagraphStyle>`，未声明时为空；需要实际完整值时使用所属目录的 `TableOfContents.titleStyle()` 和 `pageLabelStyle()`。后来修改条目 `style` 会重新计算未声明的默认排版，显式完整排版保持原值。

`isExplicit(ContentsStyle.Property)` 和不可变 `explicitProperties()` 区分未声明与显式默认值。`Property` 为 `TITLE_STYLE`、`PAGE_LABEL_STYLE`、`TITLE_COLOR`、`ENTRY_COLOR`、`PAGE_LABEL_COLOR`。`toBuilder()` 保留声明意图；`defaultTitleStyle()`、`defaultPageLabelStyle()` 和三项 `default...Color()` 分别恢复该项默认值，不清除其他声明。

旧三参数 `new TableOfContents(enabled, title, style)` 和旧 Builder 默认值保持原行为。四参数构造器的最后一项为 `ContentsStyle appearance`。旧默认的精确绘制兼容在单元测试中对照，不额外生成兼容样例页。

`TableOfContents` 的 record 组件由三项变为四项。旧三参数构造的源码调用保持可用，但不能据此保证 record 解构、反射、`equals`／`hashCode`／`toString` 或所有已编译调用方的完整兼容。调用方应重新编译；设计和兼容范围见 [ADR-0041](adr/0041-contents-appearance.md)。

## 布局、导航和边界

目录仍先分配独立前置页，页码在最终目录偏移确定后解析。标题高度按所选完整排版计算，每条目录行预留条目实际高度与页码行高的较大值；层级缩进继续为每级 14 pt，行间距仍为 8 pt。页码列固定 48 pt，不按数字位数扩宽。所选右对齐把标签贴到内容区右边缘，其他显式对齐按传入完整排版执行。

页码必须完整放入一行；字号、行框、缩进或固定列放不下时明确失败，不自动缩小、裁切或把页码拆为多行。字体缺字、诊断、取消与总页数／行数／指令预算继续走现有共享路径。每条目录链接覆盖实际行框，使用最终物理页及目标块坐标；显示页码与物理页索引仍分开处理。

本能力是目录局部外观配置，不是页区／全局品牌主题、字体角色级联、点引导线、DOCX 原生 TOC 或 PDF/UA 支持。没有新增依赖或字体资源。

## 样例与独立验收

[ContentsStyleExample](../examples/src/main/java/com/mirakyux/export4j/examples/ContentsStyleExample.java)生成一页目录和一页正文；新隔离 Maven 调用方也生成目录／正文两页，总计四页。报告目录使用居中蓝色标题、绿色两级条目和右对齐红色页码；二级长标题在扣除 48 pt 页码列后明确分为两行，正文仍为一行，链接覆盖实际两行高度。调用方保留黑色条目，分别检查显式配置与默认颜色。

~~~powershell
.\gradlew.bat :examples:contentsStyles
build/verification/python-phase13/Scripts/python.exe scripts/verify_contents_styles.py --consumer-build-directory <成功构建目录>
~~~

独立脚本读取最终 PDF 的实际字号、RGB、嵌入物理字体、CID advance、48 pt 标签列与右边界，核对正文原文、目录行框、书签及链接的 `/XYZ` 坐标和最终物理页。期望值独立声明，不从 Java 结果清单导入。四页均以 Poppler 和 PDFium 渲染；逐页目视复查完成后再登记验收，不自动更新基线。
