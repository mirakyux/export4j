# DOCX 原生字段 API

`DocxAdapter` 直接映射语义模型中的 `InlineField`。导出的字段由 Word 更新，导入后的字段由本引擎在最终分页后解析。两条路径分别决定换行和分页，不保证两者页数或坐标一致。

## 支持范围

| 模型字段 | Word 指令 | 限制 |
| --- | --- | --- |
| `PAGE` | `PAGE` | 正文与普通单元格；重复表头禁止导出此字段 |
| `PAGES` | `NUMPAGES` | 整篇物理页总数 |
| `REFERENCE_PAGE` | `PAGEREF bookmark \h` | 导入书签须位于段落或表格起点 |
| `REFERENCE_NUMBER` | `REF bookmark \w \h` | 目标为单个受支持的十进制编号段落 |
| `NOTE_MARKER` | 不支持 | 导出在写入任何字节前失败 |

导出以标准 `w:fldSimple`、书签、关系及内容类型表达字段，写入 `w:dirty="true"` 和 `w:updateFields`。初始缓存为空，不使用 PDF 页码冒充 Word 页码；消费方须更新字段才能显示值。仅为被引用块生成确定的 ASCII 书签名，原块 ID 可包含 Unicode 和 XML 转义字符。题注编号、组件字段和注释字段尚未映射。

Word 16.0 的本地实测表明：重复表头内的 PAGE 缓存会被复用，第二页仍显示第一页的值。引擎按每次呈现解析 PAGE，两者无法保持相同语义，因此该组合明确拒绝导出。总页数和固定目标引用可用于重复表头。

## 宽度与导入

字段的引擎预留宽度保存在可忽略的 `od:width` 属性中。这个属性用于模型往返，不限制 Word 自行排版的字段宽度。Word 重新保存实测会移除该属性，并把简单字段转成复杂字段。

~~~java
var adapter = new DocxAdapter();
// 自有导出文件带有每个字段的预留宽度。
var exact = adapter.read(input, DocxAdapter.Policy.STRICT);
// 标准 Word 字段缺少扩展属性时，调用方显式指定默认预留宽度，单位为 pt。
var external = adapter.read(otherInput, DocxAdapter.Policy.ALLOW_LOSS, 36);
~~~

三参数重载要求默认宽度有限、为正且不超过 `Float.MAX_VALUE`。存在的 `od:width` 优先，非法值在两种策略下均失败；不会用默认宽度掩盖损坏的元数据。两参数重载遇到缺宽度字段，严格模式失败，损失模式仅保留缓存文字并返回诊断。

导入支持简单字段和同一段落内扁平的 `begin / instrText / separate / result / end` 字段，允许指令分散在多个 run 中。已识别动态字段忽略旧缓存值，保留样式并重新解析；字段、图片和外链的原文位置按 UTF-16 计算，多个单元格段落拼接后重新定位。

指令采用白名单：PAGE／NUMPAGES，PAGEREF 的可选 `\h`，REF 必需的 `\w` 和可选 `\h`，以及单次 `\* ARABIC` 或 `\* MERGEFORMAT`。不执行公式、DDE、INCLUDETEXT 或外部数据请求。其他指令、开关、锁定字段、混合缓存样式、缺失目标及无法表达的书签位置，严格模式拒绝，损失模式保留支持的缓存文字并返回诊断。

嵌套、未闭合、重复控制符等坏字段结构、重复或未配对书签、非法几何和预算超限均失败。字段／书签各最多 100,000 个，指令最多 2,048 个 UTF-16 单元；模型每个段落／单元格最多 1,024 个字段，包大小仍受 `DocxLimits` 控制。调用方流保持借用所有权。

通用 Word 文档的样式、编号覆盖、额外部件仍受[DOCX 语义子集](production-api.md#docx-语义子集)约束。标准字段可识别不代表整个 Word 文件可无损导入；损失诊断必须由调用方审查。

## 示例与验证

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:docxFields --no-daemon --console=plain
& scripts/verify_local_consumer.ps1
# 仅在安装 Microsoft Word 的 Windows 主机执行；操作生成样例，不改用户文档。
& scripts/verify_docx_word.ps1
python scripts/verify_docx_fields.py --word
~~~

样例位于 `examples/build/docx-fields/`：标准 DOCX、严格导入后的 3 页 PDF，以及本机 Word 更新后另存的 DOCX／PDF。独立脚本检查 ZIP／XML、python-docx 读取、字段值、书签、关系、引用目标和 Poppler／PDFium 全页渲染。设计见 [ADR-0016](adr/0016-native-docx-fields.md)，证据见[阶段十二验收](verification/phase-12-docx-fields.md)。
