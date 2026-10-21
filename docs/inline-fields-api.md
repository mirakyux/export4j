# 行内字段、引用与脚注锚点 API

`Paragraph.mixed` 和 `TableCell.mixed` 支持 `InlineField`。字段与文字、显式 PNG／JPEG 图片共同换行，以一个 U+FFFC 占据原文 UTF-16 位置。长度使用 point；不读取系统字体或远程资源。

## 字段类型与调用

`InlineField(kind, targetId, width, style)` 声明字段种类、目标、固定预留宽度和行内样式。

| `kind` | `targetId` | 最终文字 |
| --- | --- | --- |
| `PAGE` | 空字符串 | 字段所在行的显示页码，包含目录偏移和分节重置 |
| `PAGES` | 空字符串 | 最终物理页总数，包含目录、尾注和脚注续页 |
| `REFERENCE_PAGE` | 目标块 ID | 目标首片段的显示页码，并生成实际页面内部链接 |
| `REFERENCE_NUMBER` | 题注／编号项 ID | 完整编号标签，如 `Figure 1`，并生成内部链接 |
| `NOTE_MARKER` | 传入 `Note` 的 ID | 按注释列表顺序生成 `[n]`；标记位置决定脚注所在页 |

~~~java
import java.util.List;
import com.mirakyux.export4j.model.InlineField;
import com.mirakyux.export4j.model.InlineStyle;
import com.mirakyux.export4j.model.InlineText;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;

var paragraph = Paragraph.mixed("body", List.of(
        new InlineText("当前页：", InlineStyle.of(12)),
        new InlineField(InlineField.Kind.PAGE, "", 30, InlineStyle.of(12)),
        new InlineText("；参见页：", InlineStyle.of(12)),
        new InlineField(InlineField.Kind.REFERENCE_PAGE, "target", 30, InlineStyle.of(12))),
        ParagraphFormat.of(12));
~~~

通过 `DocumentEngine` 或 `ComponentDocumentRenderer` 完成最终解析。目标可以位于后面的页面或分节。只调用 `DocumentLayouter` 会得到含未解析 `FieldBox` 的布局；直接绘制此布局会失败，避免输出看似成功的空字段。

字段作为原子对象参与断行，不能拆为两行。其宽度必须为有限正数；最终值必须完整放进预留宽度。值过长、目标不存在、无编号目标或最终值严格缺字都会抛出 `DocumentLayoutException`，定位块、最终物理页和原始字段标记位置。未知字体角色由正文布局入口拒绝。字段不会自动缩小，也不会在最终解析后重排正文。预留框可保留空白，调用方应根据预计页码位数和标签长度配置宽度。

`InlineStyle` 的真实字体角色、字号、颜色、基线偏移、下划线和删除线适用于字段。空角色使用默认 `FontGroup`。字段所在段落使用公共 OpenType 路径，字体和固定行距应同时覆盖正文、字段样式及图片。

每段或单元格最多 1,024 个字段，每次编排最多解析 100,000 个字段，重复表头的每次呈现也计数。生成值受文档码点预算限制，内部链接与其他交互共享总预算；最终绘制仍检查合并后的命令和码点规模。

## 语义表格单元格

~~~java
import com.mirakyux.export4j.model.CellFormat;
import com.mirakyux.export4j.model.TableCell;

var cell = TableCell.mixed(List.of(
        new InlineText("当前页：", InlineStyle.of(12)),
        new InlineField(InlineField.Kind.PAGE, "", 30, InlineStyle.of(12))), CellFormat.of(12));
~~~

单元格支持除 `NOTE_MARKER` 外的四种字段，可与图片和外部链接混排。自动列宽使用字段预留宽度；最终值只填入原框。重复表头在每个实际页面分别解析当前页码，正文拆行续页保留字段所在完整行、单元格原文范围及 `firstLineIndex`，不重新断行。引用指向目标块首片段，不能指向没有独立块 ID 的单元格。

旧的 `TableCell` 二／三／四参构造器仍可使用；新增 `fields` record 组件会影响反射和序列化结构，正式发布前需由调用方核对。字段、生成文字和链接预算由全篇共享，不能通过拆成多个单元格或重复表头绕过。

## 行内脚注和尾注

~~~java
var marker = new InlineField(InlineField.Kind.NOTE_MARKER, "note-1", 18,
        new InlineStyle("", 8, 0x285080, -3.6, false, false));
var body = Paragraph.mixed("body", List.of(
        new InlineText("这句话", InlineStyle.of(12)), marker,
        new InlineText("后面还可以继续正文。", InlineStyle.of(12))), ParagraphFormat.of(12));
~~~

调用方同时传入 `new Note("note-1", "body", "注释内容", Note.Kind.FOOTNOTE)`。一个显式标记必须对应一个同段落的注释，重复标记、未知注释和所属段落不一致都会失败。同一段落可以有多个注释，分别跟随各自标记所在行。

脚注仍按每节最多 32 次的单调预留收敛，带高最多为原正文 40%，按完整行续页。分页期间重新确定标记所在页；新到达脚注必须至少显示首行，必要时追加仅含脚注的页面。统一节内预留可能使无脚注页留白。

`ENDNOTE` 仍追加至末节正文末尾，行内标记只替代自动追加标记。未使用 `NOTE_MARKER` 的旧调用保持段末自动上标和末片段归属。注释编号沿用传入列表顺序，尚无编号重置、自动注释跳转、多栏脚注或 Word 级续页提示。

## 来源、诊断与扩展边界

`Paragraph.fields()` 保存原始 `FieldSpan`；`content()` 恢复原有图文／字段顺序。最终解析保持行数、行宽、基线、来源范围和片段位置。模板绑定先展开文字和目标 ID，再重新计算字段和图片的位置；重复模板不会自动为目标 ID 添加迭代后缀，调用方须显式绑定目标。

正文缺字诊断始终指向原文 UTF-16 范围。动态字段的诊断指向生成值的局部 UTF-16 范围，`contextualDiagnostics()` 用 `INLINE_FIELD:<原始标记位置>` 标明所属字段，同时保留最终物理页和块 ID；语义单元格另保留零基原始行、列索引。`diagnostics()` 汇总这些诊断，由调用方决定如何输出警告。

支持正文段落与语义表格单元格中的字段；语义表格组合与连续区域见[语义组合指南](semantic-components-api.md)。[语义页眉页脚](xml-page-regions-api.md)支持固定宽度 PAGE/PAGES，包含区域内表格；标题、普通组件及连续富文本组件未因此获得任意行内字段。单元格拒绝 `NOTE_MARKER`，表格脚注归属尚未定义。DOCX 已映射四类页码／引用字段，但拒绝注释标记、非编号目标的编号引用和普通正文重复表头 PAGE；固定页区内表头可使用 PAGE，见[DOCX 字段 API](docx-fields-api.md)及[页区指南](docx-page-regions-api.md)。已有块级引用和页眉页脚页码组件继续可用。

SVG／PNG 可以绘制已经解析的静态字段，但仍拒绝非空交互数据。示例显式构造无交互的预览，PDF 保留目录和引用链接。复杂文字的搜索／选择依赖阅读器对 ActualText 的支持，本批不提供 PDF/UA 标签。

## 验证与示例

~~~powershell
.\gradlew.bat build sbom publishLocalBundle :examples:inlineFields :examples:production :examples:advancedPdf
& scripts/verify_local_consumer.ps1
python scripts/verify_inline_fields.py
python scripts/verify_production.py
python scripts/verify_advanced_pdf.py
~~~

样例输出至 `examples/build/inline-fields/`：字段 4 页、脚注 3 页、双向文字 1 页、表格字段 4 页，均含 PDF 和 SVG／PNG 预览。独立脚本检查最终值、脚注实际页、链接目标与矩形、原文顺序和全部页面的 Poppler／PDFium 渲染。验证结果见[阶段十验收](verification/phase-10-inline-fields.md)与[阶段十一验收](verification/phase-11-table-fields.md)。
