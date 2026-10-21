# 组件默认样式 API

`Documents.bodyFormat` 可以为随后加入的文字组件、嵌套列、网格文字和静态表单提供统一排版。组件 Builder 未声明的属性继承；显式声明始终优先，包括显式设置的 12 pt、零缩进和黑色。完整样式继承的历史验收见[阶段十九](verification/phase-19-style-defaults.md)，设计见 [ADR-0023](adr/0023-component-style-defaults.md)；逐属性覆盖规则见 [ADR-0030](adr/0030-property-text-inheritance.md)和[阶段二十三验收](verification/phase-23-property-styles.md)。

## 统一设置正文

~~~java
var document = Documents.builder()
        .bodyFormat(format -> format.fontSize(10).lineHeight(20))
        .column(column -> column.gap(6)
                .text("情况说明")
                .column(inner -> inner.text("嵌套内容"))
                .grid(grid -> grid.columns(1, 3)
                        .row(row -> row.cells("编号", "DEMO-001")))
                .formText("☑ 已核对 □ 需补充")
                .choices(group -> group.option("通过", true).option("暂缓", false))
                .labelValue("备注", "这是合成样例")
                .text("显式小标题", text -> text.style(ParagraphStyle.of(12))))
        .build();
~~~

`bodyFormat` 按加入内容时保存快照。后续再次调用不会修改先前内容；`.add(Block)` 保留已构建语义块的原始格式，不重新解释其意图。通过 `.component(...)` 加入的预构建 Builder 组件仍可继承，因为其未设置标记保存在不可变值中。

映射到组件的属性为字号、固定行距、首行缩进、后续行缩进、对齐和断行规则。段前／段后间距、`keepTogether` 和块相邻策略继续由语义段落管理，不映射为组件属性。标题、目录和已构建语义表格保持各自的显式格式；语义便利表格仍按既有 `Document.Builder` 规则使用正文格格式。

## 局部作用域

`StyleScope` 对其后代提供默认值，嵌套作用域只覆盖自身声明的属性或颜色。具名 setter 只声明对应属性，完整 `.style(...)` 仍声明整个段落样式；字体继续通过 `FontScope` 显式控制，两者可以交错组合。

~~~java
var section = StyleScope.builder(FlowColumn.builder()
        .text("此文字继承外层字号并使用红色")
        .formText("☑ 核对完成")
        .text("此文字固定为黑色", text -> text.color(RgbColor.BLACK))
        .build())
        .color(new RgbColor(1, 0, 0))
        .build();
~~~

直接布局时也可通过 `ComponentContext.withTextDefaults(style, color)` 提供默认值。自定义组件须自行读取默认值并将原上下文传给子组件；库无法推断任意用户组件中的未设置属性。

## 覆盖与恢复继承

| 入口 | 行为 |
| --- | --- |
| `FlowText.builder("内容").build()` | 样式、颜色均未设置，继承当前作用域；无作用域时为 12 pt、18 pt 行距和黑色 |
| `.fontSize(12)` | 只覆盖字号；行距、缩进、对齐、断行规则和颜色继续继承 |
| `.style(ParagraphStyle.of(12))` | 完整样式显式固定为 12 pt，颜色仍可继承 |
| `.color(RgbColor.BLACK)` | 颜色显式固定为黑色，完整样式仍可继承 |
| `.inheritFontSize()` 等逐项恢复入口 | 仅清除该项声明；完整样式上的其他五项保持显式 |
| `.inheritStyle()`／`.inheritColor()` | 清除全部排版声明／颜色声明，恢复布局时继承 |
| `.toBuilder()` | 保留原来的未设置／显式状态，修改内容不会意外固定默认值 |
| 旧直接构造器 | 所传样式、颜色均显式；绝不根据数值是否等于默认值判断意图 |

`.style(callback)` 保留既有完整显式语义。例如 `.style(s -> s.alignment(TextAlignment.CENTER))` 在新的文字 Builder 上使用本地 12 pt／18 pt 值，再固定为居中；若只覆盖对齐，应直接使用 `.alignment(TextAlignment.CENTER)`。回调立即执行，输出不保存回调或可变子 Builder。

## 逐属性设置

需要保留父级其他属性时，直接使用具名方法：

~~~java
var document = Documents.builder()
        .bodyFormat(format -> format.fontSize(10).lineHeight(24).firstLineIndent(18))
        .column(column -> column
                .text("小标题", text -> text.fontSize(14))
                .text("右对齐正文", text -> text.alignment(TextAlignment.RIGHT))
                .text("恢复父级字号", text -> text.fontSize(14).inheritFontSize()))
        .build();
~~~

第一项只改字号，仍用父级的 24 pt 固定行距和 18 pt 首行缩进。字号不会暗中扩大继承行框；不足时明确失败。与 `ParagraphStyle.Builder.fontSize` 的本地默认行距行为不同，文字组件的部分设置以实际布局上下文为基底。

| 单项声明 | 恢复该项继承 |
| --- | --- |
| `fontSize(double)` | `inheritFontSize()` |
| `lineHeight(double)` | `inheritLineHeight()` |
| `firstLineIndent(double)` | `inheritFirstLineIndent()` |
| `subsequentLineIndent(double)` | `inheritSubsequentLineIndent()` |
| `alignment(TextAlignment)` | `inheritAlignment()` |
| `lineBreakPolicy(LineBreakPolicy)` | `inheritLineBreakPolicy()` |

这六组方法统一用于七类文字组件、`StyleScope` 与 `GridTable.CellBuilder`。四种长度单位为 point，缩进须非负，字号和行距须为正有限值；零缩进、12 pt、`LEFT` 和 `STANDARD` 也都是显式声明。子项显式属性优先于格／作用域，未声明项继续逐层继承。`StyleScope.builder(child).fontSize(14)` 只改变后代默认字号，后代已经显式设置的字号仍优先。

调用顺序具有明确意义：`.style(ParagraphStyle.of(12)).fontSize(14)` 的其他五项仍显式；再调用 `.inheritLineHeight()` 才恢复父级行距。`.inheritStyle()` 清除六项声明，但不清除独立颜色；`toBuilder()` 保留逐项状态。旧 `.style(callback)` 保持完整覆盖，不改为部分级联。设计见 [ADR-0030](adr/0030-property-text-inheritance.md)，可运行入口为 `:examples:propertyStyles`。

## 网格和续页

`GridTable.defaults(cell -> cell.textStyle(style).textColor(color))` 按行／格加入时保存快照。格 Builder 也支持同名六项具名 setter 和逐项恢复入口，例如 `.defaults(cell -> cell.fontSize(11))` 保留外层行距；`textStyle(style)` 仍完整覆盖。局部格设置优先于网格默认值；`inheritTextStyle()`／`inheritTextColor()` 清除全部格排版／颜色声明。格设置作为后代默认值传递给标准字符串格、自定义组件格和 `textFactory` 结果；其中已经显式设置的子文字仍优先。

继承在测量前解析。拆行网格的行距、表头高度、文字颜色和浮动绕排网格均使用解析后的值，不在续页或后端中重新决定样式。`StyleScope` 保留短首区、完整续区、语义插槽和诊断。

后续 `.defaults(...)` 不回写先前行；`textFactory` 产生的子组件仍按自身声明优先。

## 支持与限制

本批覆盖 `FlowText`、`TextComponent`、`OpenTypeText`、`FormText`、`ChoiceGroup`、`LabelValue`、`PageNumberComponent`，以及组合它们的列、行、网格、分栏和浮动绕排。页眉页脚可显式放置 `StyleScope`；正文 `bodyFormat` 不会修改独立页模板。

已构建的 `Table`／`TableComponent`／`SemanticTable` 具有完整语义格式，保持原值。可编辑 AcroForm 字段、独立矢量标记、边框、背景、图片、页模板和目录不由文字默认值自动重设。DOCX 仍使用语义模型，未增加嵌套组件导出。

固定行框不足、缺字、非法尺寸和无法推进的分页继续明确失败。缺字策略、真实字体变体、UTF-16 来源和结构化诊断保持原契约。`TextInheritance` 保留 `NONE`／`ALL` 和两参数构造器，增加不可变的 `overrides()` 属性集合；`style()` 表示以父样式为基底，集合中的属性使用本地值。旧组件构造调用保留源码兼容；旧 `TextInheritance` record 解构模式需要增加分量，反射分量、`toString` 和哈希也改变。项目尚未发布，不承诺二进制兼容。

相等性比较声明结构；数值相同但继承意图不同的组件不相等。恢复继承后保留的本地值仍参与相等性；`style()`／`color()` 返回配置值，有效值在具体布局上下文中解析。
