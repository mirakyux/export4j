# XML 多段单元格与原子内表

XML 版本 1 的原子块格最初已通过编译、绑定、端到端、独立输出与隔离调用方验收，见[阶段二十八验收](verification/phase-28-xml-block-cells.md)。设计见 [ADR-0035](adr/0035-xml-block-cells.md)，模型与布局契约见[块格 API](block-cells-api.md)。

## 直接按文档结构编写

`cell` 可以直接包含有序的 `p`、静态 `table`、`p`。每段都有自己的 ID、格式和原文，内表保留自己的列、行与合并格。编译器生成既有 `TableCell.blocks`，后续布局、字段和输出复用同一条语义链路。

~~~xml
<document version="1" title="检查报告">
  <p id="overview">检查记录</p>
  <table id="report">
    <columns>
      <column width="72"/>
      <column weight="1"/>
    </columns>
    <row>
      <cell column-span="2" size="10" line-height="16" align="LEFT">
        <p id="summary">${summary}，本页 <page-number width="24"/>。</p>
        <table id="details">
          <columns>
            <column width="72"/>
            <column weight="1"/>
          </columns>
          <header><cell>项目</cell><cell>结果</cell></header>
          <row><cell>编号</cell><cell>${code}</cell></row>
          <row><cell>结论</cell><cell><p id="detail-result">${result}</p></cell></row>
        </table>
        <p id="conclusion" size="11" line-height="18" align="RIGHT">结论见第 <page-reference target="details" width="24"/> 页。</p>
      </cell>
    </row>
  </table>
</document>
~~~

上例仅使用实际属性白名单。把 XML 保存为字符串 `source` 后，可以直接编译、绑定：

~~~java
import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.ParagraphAlignment;
import java.util.Map;

var compiler = XmlTemplate.builder()
        .cellFormat(new CellFormat(12, 18, ParagraphAlignment.LEFT))
        .build();
var document = compiler.compile(source).render(Map.of(
        "summary", "已完成复核",
        "code", "A001",
        "result", "正常"));
~~~

输出时仍由调用方显式配置字体和后端。编译与绑定不扫描字体、不读取资源路径，也不写目标文件。中文内容需要支持相应字形的显式字体组。

块之间可以缩进和换行。`p` 内的空白仍属于正文，因此示例把各段文字写在同一行，避免把排版缩进带入段落。

## 格式默认值

直接格内段落使用所在 `cell` 的字号、固定行高、对齐和断行策略；段前、段后间距默认为 0。`p` 的显式属性优先。显式 `size` 没有同时设置 `line-height` 时，仍采用字号的 1.5 倍。

上例 `summary` 为 10 pt／16 pt、左对齐，`conclusion` 为 11 pt／18 pt、右对齐。内表的单元格重新使用编译器的 `cellFormat`，不继承外格局部设置；因此 `detail-result` 为 12 pt／18 pt、左对齐。`bodyFormat` 继续控制顶层段落，不能用来推断格内段落的默认值。

`cell` 只接受 `size`、`line-height`、`align`、`column-span`、`row-span`。`p` 使用[总指南](xml-template-api.md)已有段落属性。没有 XML `padding`、CSS 或字体路径属性；内边距沿用既有 `TableFormat` 默认值。

## 两种内容分支

任一直接 `p` 或 `table` 子节点都会使格进入块模式。块模式只接受这两种节点及结构空白；文字、`span`、图片、链接、字段或动态节点不能与块平级混用。行内对象应放进对应的 `p`。

~~~xml
<cell>
  <p id="label">标签 <page-number width="24"/></p>
  <table id="data">
    <columns><column weight="1"/></columns>
    <row><cell>内容</cell></row>
  </table>
</cell>
~~~

上面的 `cell` 片段须放在合法表格行内。`<cell>标签<p>内容</p></cell>` 会在编译阶段失败，不能把标签文字静默丢弃。

只有行内内容的旧格保持原行为，空格仍是旧空行内格。`<cell><p id="empty"/></cell>` 则明确创建一个真实空段，保留 ID 并占用完整行框。读取模型前使用 `hasBlocks()` 区分内容；块格的旧 `text()`、`inlines()`、`images()`、`fields()` 视图会失败，完整内容从 `blocks()` 遍历。

## 外层循环与引用

块格内只写静态 `p`／`table`。现有外层 `each` 和 `rows` 可以复制整棵格内容树，无需新绑定器。

~~~xml
<document version="1">
  <p id="overview">报告说明</p>
  <each items="records" as="record">
    <table id="card">
      <columns><column weight="1"/></columns>
      <row>
        <cell>
          <p id="card-title">${record.name}，明细见第 <page-reference target="card-details" width="24"/> 页。</p>
          <table id="card-details">
            <columns><column weight="1"/></columns>
            <row><cell><p id="card-value">${record.value}</p></cell></row>
          </table>
          <p id="card-end">说明见第 <page-reference target="overview" width="24"/> 页。</p>
        </cell>
      </row>
    </table>
  </each>
</document>
~~~

绑定两条数据后，第一份的 ID 为 `card@0`、`card-title@0`、`card-details@0`、`card-value@0`、`card-end@0`；第二份使用 `@1`。同一词法复制范围内声明的静态字段目标跟随副本，第一份 `card-title` 引用 `card-details@0`。外部静态目标 `overview` 保持原 ID，内层循环也可以引用当前外层副本。

动态表格 `rows` 的每行后代使用 `@row:<表ID>:<行索引>`。例如 `rows id="records"` 的第一行段落 `row-title` 绑定为 `row-title@row:records:0`，同一行的内部引用使用同一后缀。`rows` 在 `each` 内时，还保留外层复制后缀。

XML 中继续填写静态原型 ID，不能手写 `@` 后缀或插值 `target`。循环外引用循环后代的原型 ID 不会猜测某次迭代；目标缺失时最终布局失败。条件分支的目标可以参与词法声明，但未选中分支不会产生实际锚点。

父表的动态行原型不能跨行合并，避免一个数据行占用下一行。原型内的静态内表可以保留自己的合法 `row-span`／`column-span`；内外合并规则独立验证。

## 最终字段、链接和来源

格内 `p` 支持既有四类字段、注册图片和被动图文外链。每个行内对象保留叶段落原文的 UTF-16 位置；插值后重新计算位置，不把多个段落拼成一段。图片仍须由调用方有界解码并通过 `.image(name, resource)` 注册。

PAGE、PAGES、页码引用和编号引用统一在目录偏移、分节页码确定后解析。内表与独立段落均可成为页码目标；编号引用仍须指向实际有编号的顶层对象。字段使用显式固定预留宽度，最终值放不下时失败，不重新分页或自动缩小。

每条来源路径保留外表 ID、物理行列、格内块序号及内层位置。异常和上下文警告的 `sourcePath()` 从外到内排列；`blockIndex` 是实际宿主根索引，UTF-16 属于叶原文。字段生成值的警告继续以 `INLINE_FIELD:<原始位置>` 区分来源。

## 原子布局与编译拒绝

根表计第 1 层，内表计第 2 层，第 3 层在编译时拒绝。整个块格及其父连通行组原子移页／移区；短首区可以跳过，新区域容不下则明确失败。内表在原子子树中完整呈现，不提供格内续排游标。

| 输入 | 编译行为 |
| --- | --- |
| 格内静态 `p`／`table` | 保留有序语义块 |
| 格内 `if`／`each`／`rows`、标题、列表、换页或未知节点 | 拒绝未知格内块语法 |
| 块与非空行内文字或行内节点混用 | 拒绝混合内容 |
| 第 3 层表、无效覆盖网格、块格重复表头 | `INVALID_STRUCTURE` |
| 含块格表或任意内表 `row-split="true"` | `INVALID_VALUE` |
| 动态根行原型 `row-span` 大于 1 | `INVALID_VALUE` |
| 深层重复 ID、非法格式、未知属性 | 按既有值／属性诊断拒绝 |

所有 `if` 分支在编译时检查，包含未选中分支的几何、层数、属性与互斥内容。不能把本应编译拒绝的结构留到绑定后才发现。普通行内父表头仍可逐区重复；内表自己的普通表头在完整原子呈现中只出现一次。

## 预算与输出边界

XML 源仍共用一组 `XmlTemplate.Limits`，统计全部元素、语法深度和解码文字，包括结构空白及未选分支。模型的两层表限制独立于 XML 语法深度，不能通过提高 `maxDepth` 放开第 3 层表。

绑定复用 `DocumentTemplate.Limits`，递归累计生成块、行、文字、字段目标和访问工作；每次循环复制都计费。布局再累计内外表覆盖槽、叶文字、图片及实际输出行。内表不重开一份完整引擎额度。取消与超时在同步上下文内传播，并在退出时恢复上层作用域。

DOCX 继续按标准 `w:tc` 下的有序 `w:p`／`w:tbl` 输出，复用已有字段、媒体和书签契约。XML 支持不扩大 DOCX 严格子集；重复表头 PAGE、未知格式等仍按对应适配器边界拒绝。当前阶段没有新 Word 保存／导出实测，不保证一般 Word 文档无损往返或 PDF 与 Word 同分页。

本批只扩展 XML 语法，不新增生产依赖，也不引入 Markdown 嵌套语法、HTML/CSS、include、远程资源、递归子树续排或单向长表块格。

可读样例见 [XmlBlockCellsExample](../examples/src/main/java/com/mirakyux/export4j/examples/XmlBlockCellsExample.java)。生成任务为 `:examples:xmlBlockCells`，独立脚本为 [verify_xml_block_cells.py](../scripts/verify_xml_block_cells.py)。执行命令、实际产物和逐页证据由[阶段二十八验收](verification/phase-28-xml-block-cells.md)登记；本文不把测试通过代替独立输出验收。


后续[阶段五十七](verification/phase-57-recursive-tables.md)将共享表深扩为 16 层，静态内表与块格父表均可显式 `row-split="true"`。各层拆分开关独立，编译阶段仍检查全部分支；旧原子子集的阶段记录保留。
