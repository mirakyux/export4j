# 首页与奇偶页区

`PageRegions` 为默认页眉／页脚增加 `FIRST` 和 `EVEN` 完整页区对。模型、XML、最终 PDF／SVG／PNG 及 DOCX 使用同一声明；页区仍为固定高度，内容只接受既有有界段落、图像、表格和页码字段。设计见 [ADR-0069](adr/0069-semantic-page-variants.md)，证据见[阶段六十一](verification/phase-61-page-variants.md)。

## Java 声明

```java
var regions = PageRegions.builder()
        .header(defaultHeader).footer(defaultFooter)
        .variant(PageRegions.Variant.FIRST, PageRegions.Bands.EMPTY)
        .header(PageRegions.Variant.EVEN, evenHeader)
        .footer(PageRegions.Variant.EVEN, evenFooter)
        .build();
var document = new PageDocument(body, regions);
engine.export(document).backend(new PdfBackend()).to(pdfPath);
```

`body`、各页区与显式字体由调用方提供。每个变体是完整 header/footer 对；新建变体时未设置的一边为空，不从默认对补齐。`toBuilder().inherit(variant)` 移除整对声明；显式 `Bands.EMPTY` 则保留声明并清空两边。两者含义不同。

| 条件 | 选择 |
| --- | --- |
| 本节第一页且声明 FIRST | FIRST，优先于页码奇偶 |
| 没有命中 FIRST，显示页码为偶数且声明 EVEN | EVEN |
| 其他情况 | 默认对，含奇数页 |

奇偶依据节的最终显示页码；物理页索引、目录偏移和显示页码分别处理。没有 FIRST 的节以偶数页码开始时，第一页采用 EVEN。正文分页前预留全部声明中最大的页眉与页脚 extent，即各自 height + gap，不随实际页区切换而重新分页。未选中的变体也参与绑定、ID／预算和缺失数据检查。

相等源在同类页区中共享绑定与编辑；`headers()`／`footers()` 按默认、FIRST、EVEN 顺序返回去重源。`map` 保留声明与空对，位置使用 `HEADER_FIRST` 等名称；需要共享计费的调用方应缓存输入源。模型不持有字体或 I/O 资源。

## XML

```xml
<document version="1">
  <header height="24" gap="4"><p>Default</p></header>
  <header variant="first" height="0"/>
  <footer variant="first" height="0"/>
  <header variant="even" height="32" gap="4"><p>Even</p></header>
  <section id="report" first-page-number="2"><p>Report</p></section>
</document>
```

`variant` 只能是静态 `default`、`first` 或 `even`，省略为 default。每个根／节的同类同变体最多声明一次，位于可选 page 后、正文前。片段、循环、条件及动态属性不能声明页区。

节继承根声明。覆盖某个变体的一边保留根中该变体的另一边；根中没有该变体时，未声明的一边为空。`height="0"` 空声明明确清空，不能解释成继承。XML 当前没有移除根 FIRST selector 并恢复奇偶选择的语法；Java 可用 `inherit(FIRST)`。调用使用 `renderWithPageRegions` 或 `renderSections`，旧正文 `render` 不丢弃页区。

同类变体或跨节可用静态 `source-id`／`source-ref` 复用完整页区，详见[共享来源](xml-page-regions-api.md#共享来源)。引用不补齐未声明的变体对，也不改变 FIRST 优先级。`XmlTemplateWriter` 已保存这些声明及显式空对，见[模型保存](xml-model-saving-api.md)。

## 适配器与兼容

DOCX 使用标准 default／first／even 引用、各节 `titlePg` 及全局 `evenAndOddHeaders`。全局偶数页开关启用时，没有 EVEN 的节也显式引用默认对，避免继承前节偶数页内容；可忽略扩展只保存“未声明覆盖”的意图，导入先验证它确实与默认对相同。普通 Word 区域没有固定高度时仍需显式 `RegionGeometry`。未激活的显式变体在 STRICT 下拒绝，ALLOW_LOSS 返回实际损失；错误关系、重复引用和无效几何始终失败。详见 [DOCX 页区](docx-page-regions-api.md)。

HTML／Markdown 没有对应固定分页选择，按页区策略报告损失，并把所有变体内容放入普通流。显式空 selector 也有损失记录。严格策略在输出前失败。

旧 `PageRegions(header, footer)` 和 `SectionTemplate(first, odd, even)` 源码入口保留；record 分量变化需要重新编译。旧 `SectionTemplate.Builder.first` 同时设置两种首页，随后 `firstEven` 可特化偶数首页。`PageRegionTemplates.from` 只接收无变体值；含变体使用 `sectionFrom`，避免静默丢失 selector。不保证任意 Word 无损往返或与 PDF 分页一致。
