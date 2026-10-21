# 可编辑表单样式 API

`FormStyle` 为 PDF 可编辑文字框、下拉选择、复选框和单选组提供可复用的显式外观值。它位于后端中立的 graphics API；字段目标、当前值与字体资源仍由 `Navigation` 定义。此能力与正文[静态表单](static-forms-api.md)独立，不把静态勾选图形称为可编辑控件。

## 定义与复用

~~~java
var ink = new RgbColor(.1, .2, .6);
var visual = FormStyle.builder()
        .color(ink)
        .background(new RgbColor(.9, .95, 1))
        .border(1, ink)
        .build();
var textStyle = visual.toBuilder().fontSize(13)
        .alignment(FormStyle.Alignment.CENTER).build();

var name = Navigation.TextField.builder()
        .target(target).name("reviewer").value("初始姓名")
        .fonts(List.of(chineseFont.data()))
        .style(textStyle).build();
var accepted = Navigation.CheckBox.builder()
        .target(checkTarget).name("accepted").checked(true)
        .style(visual).build();
~~~

四种字段 Builder 都提供 `.style(FormStyle)` 和同步 `.style(style -> ...)`；回调返回后保存不可变结果，不保留子 Builder。旧构造器继续使用 `FormStyle.DEFAULT`。完整构造器在旧参数末尾接受 style；布局将它原样传到 `DocumentExtras`，不依赖 PDFBox 类型。

旧构造调用和 Builder 保留。四类 Navigation／DocumentExtras record 的组件数增加，record 解构与反射调用须调整；equals、hashCode 和 toString 也包含样式，不宣称整体二进制兼容。

## 显式值与默认兼容

空 Builder 和 `FormStyle.DEFAULT` 表示没有显式覆盖，分别保留每类字段的原有外观。旧文字框的白底与浅色细框、旧下拉框的原行为、旧选择标记的方框和折线都不会被统一成另一套默认皮肤。

`fontSize`、颜色、背景、边框和对齐记录各自的显式意图。黑色、透明和零宽边框也是明确值，不能用数值是否等于默认值来判断是否设置。`toBuilder()` 保留这些选择；它修改的是新值，不回写已构建字段。

| 方法 | 行为 |
| --- | --- |
| `.fontSize(pt)` | 正数 point，文字框／下拉框的固定字号；不采用 0 自动字号 |
| `.color(rgb)` | 文字框／下拉框文字颜色；复选框／单选组勾选折线颜色 |
| `.background(rgb)` | 不透明 RGB 底色 |
| `.noBackground()` | 不绘制底色，显示页面下方已有内容；不是半透明 alpha |
| `.border(widthPt, rgb)` | 固定非负边框宽度；显式 0 不绘制框 |
| `.adaptiveBorder(maxPt, rgb)` | 宽度为 `min(maxPt, min(widgetWidth, widgetHeight) / 10)`，逐 widget 计算 |
| `.noBorder()` | 关闭边框，仍保留当前勾选标记 |
| `.alignment(LEFT/CENTER/RIGHT)` | 文字框／下拉框的水平对齐 |

复选框和单选组没有可调文字字号或文字对齐；对它们显式设置 `fontSize` 或 `alignment` 会在字段入口拒绝，不能静默忽略。应先复用只含视觉属性的基础样式，再派生文字控件的字号／对齐。

尺寸不得为 NaN／无穷。PDF 后端可表达的最小正字号、正边框宽度及标记线宽为 0.0001 pt，低于此值报 `INVALID_GEOMETRY`；自适应宽度按实际 widget 计算后也须满足这一界限。显式 0 边框合法，不画框，也不写成 PDF hairline。固定边框不会自动缩小来适应过小控件，应使用自适应边框或更大的目标矩形。

## 字体、状态与位置

空字体列表继续使用 ASCII Helvetica。Unicode 文字框和选择框仍须提供一个显式 TrueType 字体，并完整嵌入，以便保存初始值之外的新字符；样式不加载字体、不改变缺字策略，也不允许初始值缺字。固定字号不会自动缩小；当前不测量字段文字是否完整放入控件，过长或过高的内容可能被外观裁剪。调用方须选取足够大的控件，并检查实际输出。

字段当前值 `/V` 保留原文；外观的文字提取受嵌入字体的同字形映射影响。样例 LXGW 字体的“值”（U+503C）与“値”（U+5024）映射到同一 GID，PDFBox 生成的完整字体 `ToUnicode` 可能将前者提取为后者。独立验收保留此实际提取结果，逐字核对源字体 cmap、原始外观 CID 与 GID；这不表示 PDF 表单的搜索／复制能逐字还原所有 Unicode 原文。

`BlockTarget` 相对源块的首个实际片段定位，最终页包含目录偏移；`PageTarget` 直接使用最终页索引。样式只改变控件内部绘制，不重新决定正文换行或分页。

复选框和单选组的 Off／每个选项外观同时应用底色、边框与标记颜色。`noBorder()` 不会擦除勾选折线。字段树、页面 widget、当前值、默认值及选中的 AP 状态必须一致，默认保持可编辑且不要求阅读器临时重建外观。

## 样例与独立验收

[FormStyleExample](../examples/src/main/java/com/mirakyux/export4j/examples/FormStyleExample.java)用两套可复用视觉样式生成三页：目录、ASCII 控件、完整 Unicode 字体与默认兼容对照。包括显式黑色、透明背景下可见页面底色、固定零宽框、下拉框、勾选／单选、小控件、自适应边框，以及四类旧默认和显式空样式的并排比较。

~~~powershell
.\gradlew.bat :examples:formStyles
build/verification/python-phase13/Scripts/python.exe scripts/verify_form_styles.py
~~~

独立验收读取字段树、widget、全部 AP 状态、`DA`／`Q`／`MK`／`BS`，核对固定字号／颜色、内嵌完整字体、边框和背景实际绘制、最终目录偏移及勾选标记。默认对照比较实际字典与全部外观，不比较整份 PDF 字节。隔离调用方读取成功构建目录指针，也可指定 `--consumer-build-directory`。

脚本用 pypdf 修改字段、保存并重开，核对当前值与全部样式。文字外观更新限本验收小样，保留原 AP 中的背景、边框和裁剪段，再依据原 DA／Q 和嵌入字体重新放置文字；这只是独立的自动填表验证，不等于人工阅读器填写，也不证明任意阅读器重新生成外观时仍保持这些样式。原样例与保存后的全部页面均由 Poppler 和 PDFium 渲染并逐页目视复查，不更新视觉基线。

此能力不增加按钮动作、JavaScript、密码输入、富文本输入、任意边框形状或 PDF/UA；SVG／Raster 仍拒绝可编辑字段，DOCX 不把 PDF AcroForm 转成 Word 内容控件。
