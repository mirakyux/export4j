# 可保存的目录与书签声明

`DocumentNavigation` 位于 `export4j-model`，是 `SectionedDocument` 的一部分。它可以随既有 XML／资源 ZIP 和匹配的标准文件恢复信息保存；重新打开后，`DocumentEngine` 自动从当前标题创建书签和目录，不需要调用方重新拼装运行时导航。

## 创建与编辑

```java
var navigation = DocumentNavigation.builder()
        .headings(true).maxHeadingLevel(3)
        .contents(DocumentNavigation.Contents.of("报告目录", 10))
        .build();
var source = sectionedDocument.withNavigation(navigation);
var changed = DocumentEditor.edit(source)
        .replaceText("heading-a", "草稿", "定稿").execute().document();
var engine = DocumentEngine.builder(explicitFonts).build();
engine.export(changed).backend(new PdfBackend()).to(Path.of("report.pdf"));
// 持久化：XmlTemplateWriter.from(changed)，重开用 renderSections 或 renderComplete。
```

自动来源仅选择正文顶层 Heading，按节／块顺序，最大级别为 1～6；标题始终取编辑后的实际文字。层级须从 1 开始，不能跳级。手工模式使用 `.bookmark("固定标题", "block-id", 1)`，可指向正文树中的稳定块 ID；它不随目标文字自动改名。自动来源与手工书签互斥。开启目录须有实际书签和足够页区／页面预算；书签仍可在目录关闭时独立使用。

静态目标失效会在最终绑定、编辑、保存或布局时拒绝。需要删除目标时，在同一编辑请求用 `editor.navigation(revisedNavigation)` 修复；清除全部声明使用 `DocumentNavigation.NONE`。操作登记为 `SET_NAVIGATION`，不会静默删除书签或引用。

设置导航使用 `DocumentEditor.edit(SectionedDocument)`；旧 `Document`／`PageDocument` 编辑结果不能承载它，非空导航会在准备时明确拒绝，避免静默丢失。

## 排版及声明意图

`Contents` 保存 enabled、title、entries、styles 和 colors。`TextStyle` 包含 pt 字号／固定基线间距／首行及后续缩进、`ParagraphAlignment`、`LineBreakPolicy`，尺寸须为有限合法值。

`styles` 只允许 TITLE／PAGE_LABEL，未提供的属性由布局层从当前 entries 派生，规则沿用[目录样式](contents-styles-api.md)。ENTRY 已由 entries 完整表达。`colors` 可包含 TITLE／ENTRY／PAGE_LABEL，值为 `0x000000`～`0xffffff` 的不透明 sRGB；未声明与显式黑色不同。所有集合在构造时快照；`toBuilder` 保留完整声明。

手工书签最多 10,000 个，标题及目录标题各最多 10,000 UTF-16 单位，目标 ID 最多 1,024 单位。模板绑定与语义编辑把这些文字计入共享预算，Web 丢弃前也计入源预算；XML 的解析、编码、取消和输出预算照常生效。非法 XML Unicode 不能通过策略放行。

## XML

```xml
<document version="1">
  <navigation headings="true" max-level="3">
    <contents enabled="true" title="报告目录">
      <typography part="ENTRY" size="10" line-height="15"/>
      <typography part="TITLE" size="16" line-height="24" align="CENTER"/>
      <color part="TITLE" value="#000000"/>
      <color part="ENTRY" value="#224488"/>
    </contents>
  </navigation>
  <heading id="chapter-a" level="1">第一章</heading>
  <p id="body">正文</p>
</document>
```

根级导航位于可选 page／页区之后，notes／正文／section 之前，只能声明一次。手工模式先放 `<bookmark title="固定标题" target="body" level="1"/>`，再放至多一个 contents。typography 和 color 按 part 各自不能重复；未知属性／节点、非法尺寸／颜色／级别、片段内导航及动态目标均拒绝。标题和手工目标均为字面值，不插值 `${...}`，也不随 each 自动复制目标。

无 contents 时默认关闭目录；contents 默认开启、标题为 Contents、条目字号 12／行距 18。排版省略的其他属性使用零缩进、左对齐、STANDARD；颜色必须是 `#RRGGBB`。保存器写出完整条目排版，并保留标题／页码及颜色的属性存在性。

`renderSections` 保留导航，`renderComplete` 同时保留注释；`render`／`renderWithPageRegions` 无法承载导航时明确拒绝。旧 XML 不变，新语法需要更新后的读取器。

## 目标与运行时配置

PDF 使用最终分页后的实际书签、目录链接及显示页码。匹配恢复数据保留其声明；SVG／PNG 继续拒绝非空交互数据，不能把忽略输出错误当作已保存导航。

DOCX、HTML、Markdown 的严格导出拒绝导航；统一转换须显式配置 `.feature("NAVIGATION", Action.WARN, Treatment.DISCARD)` 或 IGNORE。损失报告包含该声明移除，IGNORE 只影响警告呈现。有损恢复必须从实际输出重新导入，不保存原导航副本。

模型导航非 NONE 时，非空运行时 `Navigation` 会冲突失败。运行时评论、可编辑表单、任意回调／自定义组件和字体句柄不在此声明中；需要它们时用运行时 API，并由调用方维护配置。原模板编写意图继续保存原 XML，详见[模型恢复](model-recovery-api.md)和 [ADR-0093](adr/0093-portable-navigation-and-recovery-acceptance.md)。
