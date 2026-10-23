# XML 分节

XML 用静态 `section` 声明独立纸张、页边距、显示页码和重复页区。所有节都从新页开始，绑定到模型 `SectionedDocument`，再使用同一 `DocumentEngine`。设计见 [ADR-0049](adr/0049-semantic-page-regions-and-sections.md)，证据见[阶段四十二](verification/phase-42-sections-and-docx-regions.md)。

## 模板与调用

```xml
<document version="1" title="Report">
  <page size="A4" margin="18mm"/>
  <header height="24" gap="6"><p>${company}</p></header>
  <footer height="24" gap="6"><p>PAGE <page-number width="24"/> OF <page-count width="24"/></p></footer>
  <section id="report"><p id="body">${body}</p></section>
  <section id="appendix" first-page-number="8">
    <page size="LETTER" orientation="LANDSCAPE"/>
    <p id="appendix-body">Appendix</p>
  </section>
  <section id="closing"><header height="0"/><footer height="0"/><p>Closing</p></section>
</document>
```

```java
var document = XmlTemplate.builder().build().compile(xml).renderSections(data);
var result = DocumentEngine.builder(fonts).build()
        .export(document).backend(new PdfBackend()).to(output);
try (var stream = Files.newOutputStream(docxPath)) {
    new DocxAdapter().write(document, stream);
}
```

`data` 是字符串键 Map，`fonts` 是显式 FontGroup；输出路径由调用方提供。完整资源、主题和调用见 [XmlSectionsExample](../examples/src/main/java/com/mirakyux/export4j/examples/XmlSectionsExample.java)。无 section 的模板也能使用 `renderSections`，得到单节文档。旧 `render` 和 `renderWithPageRegions` 遇到分节声明均明确失败，不只返回第一节。

## 契约

- document 在可选 page/header/footer 后只含 section，不能与普通正文混写。section 只在主源根的这一层出现，不能放在循环、条件或片段声明中。
- id 必填、静态且节间唯一；正文、表格及后代 ID 仍全文档唯一。first-page-number 默认 0 表示继续，正整数重置，物理页索引与显示页码保持分离。
- 每节先放可选 page，再放 header/footer，最后放普通正文节点。同类同 variant 最多一次；静态 variant 为 default／first／even，省略为 default。page 必须是该节原始源中的首个元素，不能通过 include 调整位置。
- 每节未声明的纸张／边距继承根配置；未声明的页眉／页脚及变体继承根页区。同一变体只覆盖一边时保留根的另一边，新建变体的另一边为空。继承来自根，不能因前一节的局部覆盖意外改变后一节。height="0" 的空页区显式清空；正高度空区只预留空间。
- 节内正文继续支持原有变量、条件、循环、动态行和有界递归 include；页区可包含展开后合法的段落／表格片段。片段不能声明 page、section 或页区。
- 主题在编译时取快照。所有分支先检查语法；实际绑定共用同一数据快照、访问／块／行／文字预算和同步取消，不为每节重置额度。继承的相等不可变页区只绑定一次。
- 页区先预留正文空间，内容必须完整放进固定区域。PAGE 使用该节最终显示页码，PAGES 使用包含目录的最终物理总页数；区域不会因数字长度重新分页。

Java 可直接使用 `Section.builder(id).pageSettings(...).firstPageNumber(...).pageRegions(...).blocks(...).build()`，再加入 SectionedDocument。便利引擎自动选择各节的模型页区；同时配置非空 Java pageTemplate 会报冲突。显式模板重载继续由调用方选择模板，用于已有 Java 首页／奇偶组合。

当前 XML section 是静态 next-page 子集；[首页／奇偶页区](page-variants-api.md)按显示页码选择。[XML 背景／前景](page-overlays-api.md)支持整层根继承／局部替换／清空；连续分节和任意 CSS 仍不支持。DOCX 的对应范围见[页区映射](docx-page-regions-api.md)。
