# 页面背景与前景

`PageRegions` 的 background／foreground 是有序 `PageOverlay` 列表。每个框包含位置、宽度和 `PageRegion` 内容；坐标以纸张左上角为原点，单位 pt。默认不预留正文空间，显式选择 `AVOID` 时正文上下避让。最终绘制顺序为背景、正文、页眉／页脚、前景，同一图层按声明顺序绘制。原有默认框不改变分页；显式指定尚不存在的物理页时补足目标页。设计见 [ADR-0070](adr/0070-fixed-semantic-page-overlays.md)和[画布扩展](adr/0107-interactive-template-canvas.md)。

## XML

```xml
<document version="1">
  <page width="360" height="260" margin="20"/>
  <background>
    <box x="20" y="100" width="200" height="24"><p>${brand}</p></box>
  </background>
  <foreground>
    <box x="20" y="220" width="300" height="20">
      <p>PAGE <page-number width="24"/> OF <page-count width="24"/></p>
    </box>
  </foreground>
  <p>Body</p>
</document>
```

每个根／节至多一个 background 和 foreground，位于可选 page 后、正文前，可与 header/footer 声明并列。box 的 x／y／width／height 必填、静态，接受 pt／mm／cm／in；位置非负、尺寸为正，并须完整位于当前节纸张内。它们不受正文边距限制。层内容可有多个框，合计最多 64 个；层级控制绘制顺序，`body-flow` 独立控制正文占位。

box 只接受既有 p／table，支持有界段落、注册 PNG／JPEG、递归语义表格及 PAGE／PAGES。页区链接、目标引用、动态几何、片段／循环／条件中的层声明和任意绘图命令仍拒绝。内容必须完整放入固定框；溢出报错，不缩放、不截断。

box 可声明 `page="2"` 指定一基文档物理页；省略或 `0` 表示在声明节每页重复，与显示页码重置无关。目标不足时组件引擎在声明节内补足空正文页，受原文档页数预算限制；目标不属于声明节或选定变体时拒绝。包含生成目录页的文档暂不接受正数 page，以免插页改变目标。

`width-mode` 接受 `FIXED`（默认）或 `FILL`（从 x 填满纸张右侧）；`height-mode` 接受 `FIXED`（默认）、`AUTO`（在剩余页高中测量实际内容高度）或 `FILL`（从 y 填满纸张下方）。width／height 数值仍须为正，动态模式的实际尺寸由最终页几何决定。AUTO 仍要求内容完整放入剩余页高，不提供跨页固定框。省略新属性保留原有语义。

`body-flow` 接受 `OVERLAP`（默认）或 `AVOID`。`OVERLAP` 不占正文空间，允许图层内容与正文重叠。`AVOID` 在绑定后的目标物理页排除框占据的纵向区间：上方正文保留，遇到框时移到其下方继续排版，空间不足则正常续页。避让覆盖正文全宽，不提供左右环绕；`AUTO` 使用测量后的实际高度，`FILL` 使用剩余页高。框完全处于正文横向范围以外时不阻挡正文。多个避让框可共同限制可用区间，仍受原有预算约束。包含生成目录页的文档暂不接受 `AVOID`，避免目录页插入后改变目标与分页。

```xml
<foreground>
  <box x="20" y="100" width="200" height="40" page="1" body-flow="AVOID">
    <p>固定位置，正文从下方继续</p>
  </box>
</foreground>
```

节未声明某层时继承根层，声明时整层替换；`<background/>`／`<foreground/>` 明确清空。继承框也须放入该节纸张。所有框在绑定时检查数据、ID 和共享预算，不等到绘制时遗漏缺失值。调用 `renderWithPageRegions` 或 `renderSections`，再交给 `DocumentEngine.export`；旧 `render` 拒绝丢弃图层。

box 可通过 `source-id`／`source-ref` 在同层复用完整内容，每个位置仍独立声明 x／y／width，高度来自来源。禁止跨类、前向或外部引用，详见[共享来源](xml-page-regions-api.md#共享来源)。`XmlTemplateWriter` 保留来源、位置、顺序与显式清空，见[模型保存](xml-model-saving-api.md)。

## Java、主题和编辑

```java
var content = PageRegion.builder().height(24)
        .paragraph(new Paragraph("brand", "Brand", ParagraphFormat.of(10))).build();
var regions = PageRegions.builder()
        .background(new PageOverlay(20, 100, 200, content)).build();
var page = new PageDocument(body, regions);
engine.export(page).backend(new PdfBackend()).to(output);
```

`content.height()` 是框高，gap 必须为 0。模型只持不可变语义，不加载字体或执行 I/O。同层的相等源可复用，绑定／编辑按源处理一次；每个放置位置和每页绘制分别计费。背景与前景仍和正文、各类页眉页脚共用 ID 命名空间；不能通过把不同内容换层绕过碰撞检查。

原四参构造器仍表示每页重复的固定尺寸框；五参构造器增加 page，七参构造器增加尺寸模式，均默认 `BodyFlow.OVERLAP`。完整构造器为 `PageOverlay(x, y, width, content, page, widthMode, heightMode, bodyFlow)`，尺寸模式使用 `PageOverlay.SizeMode`，正文处理使用 `PageOverlay.BodyFlow`。绑定、DocumentEditor 和 XML 保存／重开保留这些声明。只改变 Java `Placement.page` 的低层调用方须自行提供所需最终页面；`Placement.avoidBody` 也须在正文布局前通过页模板提供排除区，PageComposer 仅合成最终图层，不自行分页。

`DocumentTheme.backgroundStyle`／`foregroundStyle` 提供独立完整文字默认值，初始几何为 10／15 pt，未声明角色／颜色继承全局。对应 `backgroundFontRole`／`backgroundRgb` 和 foreground 方法只改一项；inherit 方法只恢复该项。完整值及回调固定两项外观，toBuilder 保留声明意图。局部 p／span 完整值优先，格内容仍使用 cell 域，不从背景段落级联。

`DocumentEditor` 保留框的位置、顺序及来源。查找／替换报告使用 `BACKGROUND:0`／`FOREGROUND:0` 等位置；共享源只报告首次实际编辑。准备好的 PDF／SVG／PNG 输出使用同一最终显示列表，字段按该节显示页码及文档物理总页数解析。

## 格式损失

| 目标 | 行为及损失 |
| --- | --- |
| PDF／SVG／PNG | 保留位置、尺寸、重复、正文避让和绘制顺序；沿用字体、预算和输出契约 |
| DOCX 直接 write | 无此语义映射，输出前失败 |
| DOCX conversionTarget | 默认失败；显式 PAGE_OVERLAYS + DISCARD 可移除整框，报告内容及定位损失；不支持虚构 PRESERVE |
| HTML／Markdown | 按 PAGE_REGIONS 策略放入节的普通流，每个源框在每节出现一次；报告重复、定位、正文避让／重叠及绘制顺序损失，字段另按 INLINE_FIELD 报损 |

WARN 和 IGNORE 的处理相同，IGNORE 仅隐藏警告；统一转换报告仍保留实际损失、位置和处理动作。预检绑定确切候选，失败不会覆盖旧目标。框内段落／表格可沿用既有内容旋转；本项不新增框级旋转、透明度、矢量形状、逐页动态位置或图层专属首页／奇偶 selector，这些不能由策略自动变成已支持。
