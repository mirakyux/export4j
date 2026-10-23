# 组件字体角色与固定富文本

`FontRoleScope` 为整个组件选择已注册字体组；`RichTextComponent` 在固定框内表达多个文字样式。两者均使用现有链式入口，不需要编写自定义绘制组件。可运行代码见 [ComponentTextExample](../examples/src/main/java/com/mirakyux/export4j/examples/ComponentTextExample.java)。

## 为组件选择字体

~~~java
var footer = FontRoleScope.builder(
        PageNumberComponent.builder("{page} / {pages}")
                .fontSize(10).lineHeight(16).build())
        .fontRole("brand").build();
var engine = DocumentEngine.builder(regularFonts)
        .fontRole("brand", brandFonts)
        .pageTemplate(page -> page.footer(
                band -> band.height(20).gap(8).content(footer)))
        .build();
~~~

`regularFonts` 和 `brandFonts` 为调用方显式加载的 `FontGroup`，后者可以包含回退字体及占位符警告策略。名称按原值匹配，不裁剪、不扫描系统字体；未注册名称在调用子组件前失败。名称与组件必须非空，名称不能全为空白。

作用域仅改变后代的默认字体组。富文本中显式设置的其他角色继续独立解析；嵌套字体作用域以最内层为准。字号、行距、颜色、页码及字体角色映射均保持原上下文。普通组件、分页组件和连续区域流使用同一入口，保留短初始区域、窄组件宽度、语义来源及诊断。接受特定文字类型的入口仍保持原类型要求，可包装整个组合组件。

需要直接传入物理字体组时，继续使用 `FontScope.builder(content).fonts(group)`。

## 编写固定富文本

~~~java
var header = RichTextComponent.builder().lineHeight(20)
        .add("REPORT ", InlineStyle.builder()
                .fontRole("brand").fontSize(14).rgb(0x224488).build())
        .add("DETAIL", InlineStyle.of(10))
        .alignment(TextAlignment.LEFT)
        .build();
~~~

所需类型位于 `components`、`model` 和 `text` 包；项目根包为 `com.mirakyux.export4j`。`InlineStyle` 是完整显式值：`of(10)` 表示 10 pt、黑色和当前默认字体组。颜色不会继承外层颜色，显式运行字号不会被段落样式改写。下划线、删除线和基线偏移也从每个运行读取；粗体／斜体须注册真实字体变体。

Builder 默认继承段落几何，可逐项设置／恢复 `lineHeight`、`alignment`、`firstLineIndent`、`subsequentLineIndent` 和 `lineBreakPolicy`。完整 `style(value/callback)` 声明整套段落样式，`inheritStyle()` 恢复继承。段落字号和 `TextInheritance.color` 不改写显式运行，因此没有组件全局字号或颜色 setter。`toBuilder()` 保留运行快照及声明意图，同步回调返回后的修改不影响已构建值。

坐标和长度使用 pt，基线偏移正值向下。固定行距必须容纳所有字号及偏移；不会自动扩大行框、缩小文字或裁切。文字可按宽度换行，组件整体仍是原子块。用于页眉、页脚、背景或前景时须完整放进固定视口；正文空间不足时整体移区，超出完整区域明确失败。页码继续使用 `PageNumberComponent`。

## 输入与资源边界

- 最多 4,096 个运行，包含空运行；总原文最多 100,000 个 Unicode 码点。
- 列表不能为空，单个空运行合法。未知角色在空运行中也失败。
- 不接受外链、U+FFFC 对象标记、图像或字段，不静默丢弃交互语义。
- 构造、追加和替换先校验并计费，失败不改变已有 Builder；布局与校验支持同步取消。
- 诊断和错误索引使用拼接原文的 UTF-16 偏移，角色保留各自缺字策略。
- 列表不可变；字体和子组件按原契约借用，作用域不关闭它们、不执行 I/O。

`rich.flow()` 返回使用相同运行快照的 `RegionFlow`，可放入 FlowColumn、GridTable 和 ContinuousColumns。`flow(PaginationPolicy)` 显式控制孤行，拒绝块邻接标志；段落在固定区域宽度塑形一次，再按完整行取片，首行缩进不在续栏重置。默认 `layout` 仍为原子组件。分页证据见[阶段五十七](verification/phase-57-recursive-tables.md)。

默认角色／颜色级联及 PDF/UA 仍有独立契约。共享复杂文字仍受[文字语料](text-corpus-api.md)所列阅读器兼容边界约束。固定富文本设计见 [ADR-0045](adr/0045-component-text-authoring.md)，原子子集证据见[阶段三十八](verification/phase-38-component-text.md)。
