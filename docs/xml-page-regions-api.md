# XML 页眉页脚与页区主题

XML 可以声明重复页眉页脚，按高度与间距预留正文空间，并在最终分页后填入页码和总页数。它复用 Java 页区布局，不需要在模板模块引入字体或 PDF 依赖。设计见 [ADR-0048](adr/0048-xml-page-regions.md)，初始文字范围见[阶段四十一](verification/phase-41-xml-page-regions.md)，图片／表格、分节和 DOCX 扩展见[阶段四十二](verification/phase-42-sections-and-docx-regions.md)。

## 模板与调用

```xml
<document version="1" title="Report">
  <page size="A4" margin="18mm"/>
  <header height="24pt" gap="6pt"><p>${company}</p></header>
  <footer height="24pt" gap="6pt">
    <p align="RIGHT">PAGE <page-number width="24"/> OF <page-count width="24"/></p>
  </footer>
  <p>${body}</p>
</document>
```

```java
var theme = DocumentTheme.builder()
        .headerStyle(style -> style.fontRole("bold").rgb(0x224488)
                .format(format -> format.fontSize(11).lineHeight(18)))
        .footerStyle(style -> style.rgb(0x336633))
        .build();
var template = XmlTemplate.builder().theme(theme).build().compile(xml);
var document = template.renderWithPageRegions(Map.of(
        "company", "Example", "body", "Report content"));
var result = DocumentEngine.builder(fonts)
        .fontRole("bold", boldFonts)
        .build().export(document).backend(new PdfBackend()).to(output);
```

`fonts` 与 `boldFonts` 是调用方从显式字体资源加载的 FontGroup；`output` 是 Path。相关导入来自 `com.mirakyux.export4j.model`、`adapter.template`、`components` 和 `backend.pdf`。完整例子见 [XmlPageRegionsExample](../examples/src/main/java/com/mirakyux/export4j/examples/XmlPageRegionsExample.java)。

单节页区模板使用 `renderWithPageRegions`，返回不可变 PageDocument；也可统一使用 `renderSections` 获得 SectionedDocument。旧 `render` 返回 Document，遇到页区会抛出 `BindingException/UNSUPPORTED_BLOCK`，防止遗漏。无页区模板继续使用旧入口，也可使用新入口获得相同布局。

## 语法与主题范围

| 项目 | 契约 |
| --- | --- |
| 声明位置 | 主源 document 或 section 的可选首个 page 之后、正文之前；header/footer 各一次，二者顺序不限 |
| 高度与间距 | height 必填非负数，gap 默认 0；零高度必须为空且 gap 为 0；无单位为 pt，也支持 pt／mm／cm／in；禁止插值 |
| 区域内容 | 有序 p／table，含两层原子块格；总语义块不超过 256；空区域可只预留空间；条件／循环或片段不能声明区域 |
| 行内内容 | 文字／变量、span、显式登记的 image、固定宽度 page-number/page-count；字段沿用 width 的 pt 数值规则 |
| 主题 | headerStyle/footerStyle 各有独立完整段落格式、默认 fontRole 与 RGB；默认 10 pt／15 pt 行距、左对齐、无段间距、默认字体、黑色 |
| 局部覆盖 | p 的 size／line-height／align／role／color 等与正文规则一致；空 role 选择默认字体，#000000 显式恢复黑色 |
| 命名样式 | p style 替换完整几何，span style 替换完整 InlineStyle；行内局部属性最后覆盖，不按数值猜设置意图 |
| 标识与绑定 | 正文和两区 ID 共用命名空间；变量来自同一根数据快照，访问／块／码点预算与正文共享 |

主题在 XML 编译时解析为完整段落和运行，绑定时只替换文字。`bodyText`／`cellText` 与正文格式不改变页区，修改主题不回写已编译模板。嵌套回调同步运行并在返回时取值快照。

## 布局、字段与诊断

正文上边界为原上边距加页眉 height+gap，下边界扣除页脚 height+gap。页眉位于原上边距，页脚位于原下边距上方；固定区沿用语义段落换行、缩进与段间距规则。区域过高、正文空间耗尽或内容产生第二页都会失败，不裁切和自动缩小。

每次实际呈现才解析 PAGE/PAGES：当前页使用最终显示页码，总页数包含前置目录。字段占固定宽度，替换不会改变已确定的正文分页；数字必须放进预留的一行。具名角色仍须在引擎登记，缺字沿用相应 FontGroup 的错误或占位警告策略。

结构／属性错误在编译时抛出带行列的脱敏 TemplateException；数据错误使用 BindingException。变量展开后仍须满足模型上限，模型校验可抛出 IllegalArgumentException。布局失败保持既有几何／字体异常；原子路径输出不会因失败覆盖旧目标。

每区还限制 4,096 行内项、16,384 表格结构槽和 100,000 源码点。局部布局最多一页、10,000 输出行；最终呈现受整文档组件命令／行预算和同步取消控制。页区重复不重新展开数据。诊断 region 为 HEADER/FOOTER，页索引为最终零基物理页；UTF-16 位置按语义树访问顺序拼接各段落和表格普通锚点格原文，不插入分隔符；块格后代按其语义访问位置追加。生成字段缺字警告定位到对应 U+FFFC 标记。

## Java 组合与输出边界

`PageRegionTemplates.from(document.pageRegions())` 只接收无变体声明的值，返回普通 PageTemplate；含首页／偶数变体使用 `PageRegionTemplates.sectionFrom` 返回 SectionTemplate。可进一步组合 Java 背景／前景，随后显式使用 `document.document()` 的语义内容和已有分节入口。PageDocument 便利入口自动处理所有变体，与引擎上配置的 pageTemplate 同时出现时会拒绝冲突。

PDF、SVG、PNG 消费最终显示列表；SVG／PNG 继续拒绝非空交互数据。样例中的 PDF 保留目录链接，静态 SVG 预览显式只选择绘制页面。DocxAdapter.write 直接接受 PageDocument／SectionedDocument，映射[标准页眉页脚](docx-page-regions-api.md)；显式取 document() 仍仅选择正文。

[XML 分节](xml-sections-api.md)支持根默认值、各节纸张／边距／页码、页区替换及清空。页区图片使用行内 image，表格使用标准 table／columns／row／cell 语法；表格格样式沿用 cell 默认值，p 使用独立页区默认值。静态 `variant="default|first|even"` 支持[首页／奇偶选择](page-variants-api.md)，同类同变体最多声明一次。[XML 背景／前景](page-overlays-api.md)另用固定 box 声明，沿用最终组合路径；页区链接／目标引用和更广品牌组合仍开放。

`header`／`footer` 可选 `edge`、`left`、`right` 长度（pt／mm／cm／in）。`edge` 表示页眉距纸张顶边或页脚距纸张底边；`left`／`right` 分别是距纸张左右边。未声明时沿用页面对应边距，显式 `0` 可贴边。正文顶部／底部预留至少覆盖页区高度与 `gap`，页区超出纸张会拒绝；共享 `source-ref` 复用完整位置设置，引用处不能覆盖这些属性。

## 共享来源

header／footer 和图层 box 支持静态 `source-id` 定义与 `source-ref` 引用，用于跨节、同类变体或同层多个位置复用完整内容：

```xml
<document version="1">
  <header source-id="brand" height="24" gap="4"><p id="brand-text">${company}</p></header>
  <header variant="even" source-ref="brand"/>
  <background>
    <box source-id="logo" x="20" y="100" width="140" height="24"><p id="logo-text">Brand</p></box>
    <box source-ref="logo" x="180" y="100" width="140"/>
  </background>
  <section id="first"><p>Body</p></section>
  <section id="next"><header variant="first" source-ref="brand"/><p>Next</p></section>
</document>
```

四类来源（header、footer、background、foreground）分别在本次主源编译中全局命名，来源名遵循 `[A-Za-z_][A-Za-z_0-9-]{0,127}`，与语义块 ID 独立。只引用前文同类定义，不能前向引用、跨类引用、声明别名或访问外部资源。section 内首次定义也可供后续节同类引用，不改变节间根继承规则。

引用必须为空，不能同时声明 source-id、height、gap 或子内容；页眉页脚保留本次 variant，box 必须声明自己的 x／y／width。被引用的完整来源提供 height／gap 和已经解析的块／运行；引用处不重新应用主题。每个放置位置仍独立检查纸张包含关系与布局容量；source-id 定义处仍要满足原有 height 和区域内容限制。

同类相等来源共享绑定、编辑和源块／码点计费，引用元素本身继续占解析元素／源码预算，放置和每页绘制分别计费。普通块 ID 仍全文档唯一，不通过复制块或重命名隐藏共享关系。旧编译器不识别这两个新属性。模型保存见 [XmlTemplateWriter](xml-model-saving-api.md)，设计与独立验收见 [ADR-0085](adr/0085-shared-xml-page-region-sources.md)和[阶段七十九](verification/phase-79-xml-region-saving.md)。

## 复现

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:xmlPageRegions
.\scripts\verify_local_consumer.ps1
build/verification/python-phase13/Scripts/python.exe scripts/verify_xml_page_regions.py
```

隔离 Python 需先安装 `scripts/requirements-verification.txt`，Poppler 需在 PATH 中。Linux/macOS 使用 `./gradlew` 及对应 Python 虚拟环境，并执行等价的隔离 Maven 调用方任务。生成物位于 `examples/build/xml-page-regions/`，独立检查结果位于 `build/verification/xml-page-regions/`。
