# 静态表单 API

静态勾选、选项组、标签和值以及填写线位于 `export4j-components`。所有长度使用 point，局部坐标原点位于左上角。输出支持 PDF、SVG 和 Raster；这些组件不生成可编辑字段，也没有 DOCX 语义映射。可编辑表单继续使用 `Navigation` 字段入口。

## 编写表单

~~~java
var form = Documents.builder()
        .title("检查记录")
        .column(column -> column.gap(6)
                .formText("报告类型：☑ 首次报告  □ 跟踪报告")
                .choices(group -> group
                        .kind(ChoiceGroup.Kind.RADIO)
                        .option("通过", true)
                        .option("需复核", false))
                .labelValue("病例编号", "DEMO-001",
                        field -> field.labelWidth(72).gap(8))
                .fillLine(line -> line.height(18).bottomInset(3)))
        .build();
~~~

所需项目类型是 `Documents`、`ChoiceGroup`；导出仍显式提供字体和后端。可运行样例见 [StaticFormsExample](../examples/src/main/java/com/mirakyux/export4j/examples/StaticFormsExample.java)。密集中文表单见 [AdrFormExample](../examples/src/main/java/com/mirakyux/export4j/examples/AdrFormExample.java)。两者均使用虚构数据。

## 组件契约

| 类型 | 行为与入口 |
| --- | --- |
| `FormText` | 保留普通文字，将 `□`／`☑` 识别为未选／选中复选框，将 `○`／`●` 识别为未选／选中单选圆点；`builder().append(...).checkbox(...).radio(...)` 可直接拼写 |
| `ChoiceGroup` | `.option(label, selected)` 声明选项；默认复选，单选组最多选中一项；`.vertical(true)` 每个选项另起一行 |
| `CheckMark` | 独立固定尺寸矢量标记，`.kind(...)`、`.selected(...)`、`.size(...)`；默认 9 pt 方框 |
| `LabelValue` | 左列使用固定 `.labelWidth(...)`，右列扣除 `.gap(...)` 后换行；已消费的标签不在续页重复；默认 72 pt／8 pt |
| `FillLine` | 按视口宽度绘制填写线，默认高度 18 pt、下边距 3 pt、线宽 0.5 pt；描边完全位于视口内 |

`FormText`、`ChoiceGroup` 和 `LabelValue` 按完整行续页或续栏，短首区不足一行时跳过。横向选项组按照普通行内文字换行，长标签内部也可换行；不承诺每个选项整块移动。单选组可以没有默认选择，多个默认选择明确失败。

`FormText` 的四个标记字符是保留语法。需要普通字体圆点时使用 `FlowText`。`ChoiceGroup` 的标签不能包含这些标记，避免把标签解释成额外选择。其他字符仍使用显式字体组；缺字报错或占位符警告策略继续生效。

表单文字沿用共享 OpenType 路径，支持双向文字、组合字符和 UTF-16 诊断。方框和圆点不请求字体字形；标记预留宽度等于字号，可见尺寸为字号的 72%。文字、标记与字体组共用固定行框；行框不足明确失败，不自动缩字。带阿拉伯文字体的示例显式采用更大的行距。

## 合并格与固定区域

~~~java
var options = FormText.builder("☑ 第一项\n□ 第二项\n☑ 第三项").build();
var table = GridTable.builder().columns(1, 3).allowRowSplit(true)
        .row(row -> row.cell("说明", cell -> cell.rowSpan(2))
                .cell(options, cell -> cell.rowSpan(2)))
        .row(row -> { })
        .build();
~~~

`FormText` 和 `ChoiceGroup` 接入标准合并格准备流，测量一次，完整行续排。每片段保留边框、内边距和重复表头；标记及短格标签不重复。默认 `allowRowSplit(false)` 仍整组合并行移动。

独立 `CheckMark` 和 `FillLine` 是原子组件。固定页区只接受单片段；超高 `FormText` 明确失败。任意自定义 `RegionFlow`、嵌入 `LabelValue` 的合并格自动拆分均未新增支持，继续遵守[续页指南](grid-continuation-api.md)的标准子组件边界。

## 布局扩展与限制

文字模块新增中立值 `InlineGraphic(width, ascent, descent, ink)`。布局入口接受以原文 U+FFFC 的 UTF-16 位置为键的显式图形映射。图形与图片／字段位置不能重叠，不能拆开字形簇或跨样式边界。轮廓在测量时定位，后端直接绘制，不重新决定换行。

图形局部轮廓控制点须处于预留宽高内；每个图形最多 10,000 个路径段和 1,024 个逻辑 UTF-16 单元，每段文字最多 1,024 个图形，累计轮廓段受现有 2,000,000 段预算限制。逻辑文本拒绝非法 Unicode 和控制字符。显式注入的简单塑形器拒绝行内图形，只有默认共享排版入口支持。

`InlineGraphic` 不增加语义模型类型。静态选择状态写入逻辑文字，但不等于阅读器通用搜索／选择兼容或 PDF/UA。字体和真实标签的诊断继续按原文 UTF-16 返回，不输出库内部日志。

定位 `TableComponent` 仅用于单视口静态表格。它现在在构造时拒绝字段、文字／图片外链及具名字体角色，避免上下文丢失或链接静默消失。这些内容应继续使用顶层语义 `Table`。该提前拒绝属于尚未发布 API 的行为修正；普通富文本、无外链图片和单页尺寸约束保留。

决策见 [ADR-0020](adr/0020-static-form-components.md)，验证见[阶段十六](verification/phase-16-static-forms.md)。
