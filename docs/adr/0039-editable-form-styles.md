# ADR-0039：可编辑字段的显式外观声明

- 日期：2026-10-16
- 状态：已采纳；实施验证中，验收结果单独记录
- 关联：[组件 API](../components-api.md)、[进阶 API](../advanced-api.md)、[组件默认文字样式](0023-component-style-defaults.md)、[编写主题快照](0037-authoring-theme-snapshots.md)

## 背景

可编辑文本、下拉框、复选框及单选组已有后端中立的数据、最终页定位、值和默认值，以及完整字体和可编辑外观。它们仍使用固定外观，调用方无法声明字号、文字／标记颜色、背景、边框和文字对齐。静态 `FormText` 和文档正文主题不负责可编辑字段，不能把正文格式直接套到 PDF 控件上。

四类控件原有默认值不同。文本框具有白底、蓝灰边框和自适应边宽；下拉框没有显式 MK／BS；复选框与单选组使用自研方框／折线外观。使用一个新的完整默认样式覆盖全部控件会改变现有输出，按数值是否等于默认值推断用户意图也会丢失显式黑色、零边框等声明。

## 决策

在 `export4j-graphics-api` 增加不可变 `FormStyle`，只通过具名 Builder 构造。该值同时被未解析的 `Navigation.Field` 和最终 `DocumentExtras.FormField` 使用，不持有 PDFBox 类型、字体句柄或可变资源。

五个独立显式属性为 `FONT_SIZE`、`COLOR`、`BACKGROUND`、`BORDER`、`ALIGNMENT`。未设置的属性沿用具体控件原有默认值；`DEFAULT` 的显式集合为空。返回的集合不可修改，`toBuilder()` 复制数据和声明，`equals`／`hashCode` 包含声明意图。没有数值猜测、主题树、全局样式缓存或隐式字体扫描。

`fontSize(pt)` 必须有限且大于零，不接受 PDF 的零字号自动适配。`color(RgbColor)` 控制文本／所选选项的文字颜色或复选／单选的标记颜色。`background(color)` 与 `noBackground()` 分别声明不透明背景和透明背景。`alignment(LEFT/CENTER/RIGHT)` 只适用于文本及下拉框；复选框和单选组在公共构造入口拒绝显式字号和对齐，不静默忽略。

边框是一个完整属性，同时保存模式、宽度和 RGB。`border(width,color)` 使用固定非负点宽，显式零隐藏边框；`adaptiveBorder(maximum,color)` 的有限正上限按实际控件最短边的十分之一封顶；`noBorder()` 明确禁用边框。标记笔宽独立自适应，禁用边框不会隐藏已选标记。边框属性不拆成颜色／宽度的隐式级联。

## 传播与兼容

四类 `Navigation`／`DocumentExtras` record 在末尾增加 `FormStyle style`，保留全部旧参数个数的构造器并委托 `DEFAULT`。两个字段接口均提供 `style()`。四个已有 Navigation Builder 增加 `style(value)` 和同步 `style(callback)`；回调从当前值复制，返回前立即 build，不保留可变子 Builder。随后修改捕获的子 Builder 不影响已经配置的字段，连续回调保留之前的显式声明。

`ComponentDocumentRenderer` 仍先解析目标、目录前置页和实际物理页，再原样传递不可变样式。此变更不修改布局、组件正文风格、字段树、字段名称、初始值验证、字体策略或交互预算。

旧构造调用和 Builder 保留，但 record 组件数与规范构造器发生变化，解构／反射使用须调整；equals／hashCode／toString 纳入样式。该源码便利兼容不等于整体二进制兼容承诺，当前项目仍未正式发布。

默认文本框仍使用 10 pt 黑色、白色背景、RGB `(0.6,0.7,0.8)` 边框及 `min(0.7 pt, 最短边 / 10)` 边宽。默认下拉框仍使用 10 pt 黑色，不新增 MK／BS。默认复选／单选仍白底，边框与标记均为 RGB `(0.2,0.3,0.4)`，边宽为 `min(1 pt, 最短边 / 10)`。单选组仍为等距方形 widget 和折线标记，不借此变更成圆形视觉。

## PDF 外观与数值

文本和下拉框在设置当前值之前写入 DA 字号／RGB、显式 Q，以及显式背景／边框的 MK／BS，再由既有 PDFBox 路径生成 AP。未声明的控件属性保持原来字典是否存在的状态。显式黑色、白色背景、LEFT 和零边框都生效。

实际解析边宽为零时，同时保存 BS/W=0 并移除 MK/BC。PDFBox 3.0.8 在存在 BC 时先使用 1 pt 笔宽，只有严格正的 BS/W 才覆盖；仅保存零宽而保留颜色仍会生成可见边框。独立验收和逐页检查发现此问题后，生产链已修正，并增加文字框／下拉框的实际 AP 无描边回归；模型仍保留显式 BORDER 的模式、零宽和原颜色，不靠清除用户声明修复后端。

复选框生成 Off／Yes 外观；单选组为每个 widget 生成 Off／对应选项外观。全部状态使用同一份外观声明，不能只修改当前选中状态。每个单选 widget 根据自身尺寸解析边宽，不用整个组的宽度。字段／widget 的 V、DV、AS 和 AP 状态对应关系保持。

正固定边宽必须满足 `2 × width < widget 最短边`，且转换为 PDF 浮点后仍有正内框；不满足时报告 `INVALID_GEOMETRY`，不静默缩小。自绘边框的 X／Y 内缩分别为原 5% 与半笔宽的较大值，防止粗边超出 BBox；默认自适应边宽保留原有 5%／90% 方框和勾选折线几何。文字不自动缩小以适应字段。

DA 数字使用检查后的有限浮点值，序列化为无科学计数法的合法 PDF 十进制。正数转换溢出、下溢或控件矩形塌缩时报告 `INVALID_GEOMETRY`，异常带最终零基页号，不包含字段值或资源路径。

PDFBox 3.0.8 的 appearance 内容流默认只保留四位小数。以已解析的依赖 jar 执行 `javap -private -c`，可见 `PDAbstractContentStream` 构造器调用 `NumberFormat.setMaximumFractionDigits(4)`；`PDPageContentStream(PDDocument,PDAppearanceStream)` 沿用该设置，`writeOperand(float)` 通过 `NumberFormatUtil.formatFloatFast` 或同一格式器输出。单纯通过有限 float 检查的极小正笔宽仍可能写成零，变为 PDF hairline，极小正字号也可能改变含义。

因此，本后端要求实际解析成 PDF float 的正字号、边宽和标记笔宽至少为 `0.0001 pt`；更小值报 `INVALID_GEOMETRY`，不自动放大或生成 hairline。显式零边框／禁用边框保持合法，未绘制的默认边框不触发该下限。RGB 通道不是长度，不套用此尺寸阈值。该下限不表示所有小数几何完全无舍入，也不承诺任意极小 widget 可用。

空字体列表仍仅允许既有 ASCII 范围；Unicode 字段仍完整嵌入一个显式 TrueType 字体，初始缺字失败，CFF 字段拒绝。正文占位符策略不作用于字段值，字段／正文相同字体继续按既有 ID 去重和预算计算。

## 验证与范围

行为验证覆盖五项显式意图、非法值、快照、旧构造器、四类兼容默认值、透明背景、零／固定／自适应边框、标记独立笔宽和不适用属性拒绝。后端核对 DA、Q、MK／BS、所有 AP 状态、默认／当前值、最终页号及 Unicode 完整字体。独立工具检查最终 PDF，并双渲染全部新页；自动改值／保存回读只称为程序验证，不冒充人工阅读器操作。

公共签名经隔离 Maven 调用方验证，现有组件、进阶及生产表单回归。不新增模块、依赖或资源，不引入文档主题对字段的隐式继承，不承诺外部阅读器重新生成外观后与本引擎完全相同。静态控件、PDF/UA、自动字号、任意图形按钮、脚本和动作仍不属于本次能力。
