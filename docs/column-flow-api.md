# 可编辑的正文分栏

`ColumnFlow` 将段落与语义表格保存为一条连续内容流，按从上到下、再从左到右的顺序填充 1～3 栏。编辑文字后由布局引擎重新分栏和分页；PDF、SVG、PNG 消费最终布局，不在后端重新排版。Java 组件形式的 `ContinuousColumns` 继续使用原有契约，见[组件分栏指南](continuous-columns-api.md)。

## XML 与 Java

```xml
<document version="1">
  <heading level="1">通栏标题</heading>
  <columns id="before" count="2" gap="18" balanced="true" weights="1 1">
    <p id="intro">第一段正文。</p>
    <each items="paragraphs" as="paragraph"><p>${paragraph}</p></each>
  </columns>
  <table id="wide-table">
    <columns><column weight="1"/><column weight="2"/></columns>
    <row><cell>项目</cell><cell>横跨正文宽度的说明</cell></row>
  </table>
  <p id="wide-figure" line-height="106"><image ref="figure" width="400" height="100" alt="通栏示意图"/></p>
  <columns id="after" count="2" gap="18" balanced="true" weights="1 1">
    <p id="conclusion">通栏图表下方继续分栏。</p>
  </columns>
</document>
```

调用方通过 `XmlTemplate.Builder.image` 显式提供 `figure` 资源和所需字体。普通根级表格、图片段落占正文全宽；前一段分栏按实际最高栏结束高度交回正文，后段从剩余高度继续，放不下时正常续页。三个容器之间没有隐式分页符。

```java
var columns = ColumnFlow.builder("article")
        .columns(3).gap(18).balanced(true)
        .columnWeights(List.of(1.0, 2.0, 1.0))
        .add(new Paragraph("intro", "Continuous body text.", ParagraphFormat.of(11)))
        .build();
```

模型位于 `export4j-model`，模板适配器仍仅依赖语义模型，不反向依赖组件。列表在配置和构建时快照；块 ID、原文 UTF-16、字段、图片及链接沿真实段落／表格保留。首版支持边界以以下表格为准。

| 配置 | 契约 |
| --- | --- |
| `count`／`columns` | 整数 1～3，XML 默认 2 |
| `gap` | 非负有限 pt，默认 18；须为实际栏宽留下正空间 |
| `balanced` | XML 与 Builder 默认 true，最多 24 次确定性试排缩短公共栏高 |
| `weights`／`columnWeights` | 空表示等宽；否则恰好每栏一个正有限比例，XML 使用空白分隔 |
| 子内容 | Paragraph、Table；XML 支持 p、table、rows、if、each、include |
| 空内容 | 静态空容器拒绝；动态分支／循环全部为空时省略容器，仍计绑定访问预算 |
| 位置 | 正文或分节正文；禁止在格内、固定页区或另一分栏容器内嵌套 |
| 暂不支持 | 列内 Heading／列表／题注／脚注及尾注锚点／显式分页符、page-break-before、keep-with-next 链；不支持的组合明确失败 |

标题可作为通栏根级块；列内小标题可使用加粗普通段落。源码格式、ID 限制、动态变量及片段作用域沿用 [XML 模板](xml-template-api.md)。

表格声明的 Fixed 总宽／Auto 最小总宽超过当前栏宽时可跳到后续宽栏。复杂格内 padding、递归固定内表等几何不满足仍明确失败，不保证任意表格在过窄栏中自动恢复。列内小标题目前没有与下一段联排的 keep-with-next 保障。

分栏从页中已有正文后开始时，保留前一块的段后距和首个子块的段前距；真实页首、续栏和续页抑制边界间距。结束后，以最高栏的实际底部接续正文，并将最后一个逻辑子块的段后距与下一块的段前距相加；间距不会单独生成空白页。

## 编辑和保存

设计器的“正文分栏”组件提供栏数、栏距、比例及平衡配置，列内段落和表格可在画布编辑、插入与排序。选中直属段落后，画布工具条提供“插入通栏表格／图片”，无须先进入结构树。直属段落／表格还可显式复制和删除；正在输入时先完成文字编辑，分栏至少保留一个内容块。

正在编辑直属段落时，“光标处通栏表格／图片”按当前 ProseMirror 光标位置拆成前后两段，在中间放入通栏对象，再恢复相同的分栏设置。拆分保留段落属性、嵌套 span、链接、变量原文、行内图片及字段；变量、图片和字段按一个编辑对象定位，文字位置使用 UTF-16。非空文字选区须先收起，输入法组合输入须先完成，不通过插入操作隐式删除文字。

光标位于段首／段尾时按块边界插入，不额外制造空段落或空分栏。前段保留原 ID，确需拆出的后段及后续分栏分配唯一 ID，分栏属性、源码注释和后续节点顺序保留。图片资源选择取消时不提交拆段或分栏结构；选定后，通栏对象和结构拆分作为一次修改提交并可一起撤销。源模板或编辑会话已变化时，拒绝使用过期的光标位置。

循环／条件内部的子节点不能直接拆出通栏对象；可选中整个动态节点作为插入边界，保留表达式和作用域。设计器提供双栏、三栏样例，各包含中间通栏表格／图片和恢复后的分栏正文。

这项编辑能力生成“前段分栏 → 通栏对象 → 后段分栏”的语义结构，不提供正文绕过仅跨部分栏的浮动对象。交互及本轮边界见[设计器指南](designer.md#便捷编辑与精确排版)和[后续编辑流程验收](verification/designer-workflow-v2.md)。

保留变量、条件和循环时保存原 `XmlTemplateBundle` 源码。保存已绑定的模型使用 `XmlTemplateWriter`，保留真实分栏容器、属性、子块和图片；绑定后的数据不会反推为原动态模板。两条路径均可保存为普通 ZIP，重开后继续编辑。

循环绑定生成的 `id@0` 等 ID 不能直接写回静态 XML 标识符，沿用 writer 的 `IDENTIFIER` 显式 DEGRADE 重命名及目标重定位规则；默认严格保存会拒绝。合法静态 ID 直接保留，原动态模板源码保存不需要这一步重命名。

本批列内编辑通过 XML 源码或设计器完成。Java `DocumentEditor` 的列内定位、查找和结构编辑尚未接入；不能把模板编辑的验证扩大为该 API 的分栏后代编辑支持。

## 转换边界与栅格

DOCX、HTML、Markdown 当前没有原生分栏映射。直接导出明确拒绝；统一转换必须为 `COLUMN_FLOW` 显式选择 `DEGRADE`，按阅读顺序输出所有子段落／表格，并报告栏数、比例、栏距和平衡布局损失。WARN／IGNORE 不隐藏实际损失；不以 DISCARD 删除整段正文。Word 原生数学公式是独立的[数学适配器](docx-math-api.md)能力，不由此获得 Word 原生分栏。

DOCX 降级时，指向分栏容器的 `REFERENCE_PAGE` 重定向到首个保留子块，并逐字段记录 `PAGE_REFERENCE_TARGET_REPLACED_WITH_FIRST_RETAINED_BLOCK` 损失；原子块 ID、字段样式和前后引用顺序保持。首个子块可以是真实空段落或表格；没有子块的静态容器仍拒绝。

正文分栏与栅格解决不同问题。连续文章不需要给每个段落指定格子；通栏插图通过相邻正文块表达。需要“跨三栏中的两栏”或显式图文并排时，可使用[可编辑 GridLayout 栅格](grid-layout-api.md)，通过相邻正文块与 ColumnFlow 组合。栅格项按明确轨道放置、整行原子分页，首版不支持容器互嵌。现有 Java `GridTable` 及 HTML/CSS Grid 子集继续使用各自 API。

设计见 [ADR-0109](adr/0109-semantic-body-columns.md)，发布调用方见 [ColumnFlowConsumer](../verification/consumer/src/main/java/consumer/ColumnFlowConsumer.java)，独立验收见[正文分栏验收](verification/semantic-body-columns.md)。
