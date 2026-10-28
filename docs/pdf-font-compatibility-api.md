# 外部 PDF 的字体兼容

页面渲染、内容流文字替换、光栅脱敏及语义恢复共用字体资源预检，并分别检查实际操作能否表达源内容。有效程序按确切类型解析；不扫描系统字体，也不创建隐式替代字体。能够渲染 Type3 不表示能够恢复其中的可编辑文字。

## 接受的字体程序

| PDF 字体声明 | 嵌入位置及程序 |
| --- | --- |
| TrueType | FontDescriptor／FontFile2：静态 TrueType |
| Type0／CIDFontType2 | 后代 FontDescriptor／FontFile2：静态 TrueType |
| Type1 | FontDescriptor／FontFile3，Subtype 为 Type1C：单一名称索引的 CFF1 程序 |
| Type0／CIDFontType0 | 后代 FontDescriptor／FontFile3，Subtype 为 CIDFontType0C：单一 CID 索引的 CFF1 程序 |
| 上述两类 CFF 字体的 OpenType 容器 | FontFile3 的 Subtype 为 OpenType：静态 OTF／CFF，程序种类须与 PDF 字体声明一致 |
| 传统 Type1 | FontDescriptor／FontFile：按 PDF 的 Length1／Length2／Length3 验证明文、加密程序及尾段；检查真实 CharString、Subr 和 seac 执行 |
| Type3 | 原生 CharProcs、Encoding、Widths、FontMatrix 和 FontBBox 的有界绘制；实际 CharProc 及其资源必须存在、合法并受执行额度控制 |

检查覆盖全部资源，包括未使用字体、Form、当前批注外观、ExtGState 字体与软蒙版组。先对输入流执行解码额度预检，再解析确切字体程序；字体种类、容器及嵌入字段不得冲突。同一程序流在一次资源检查内复用验证，不能用不同字体声明绕过检查。

未嵌入字体、可变字体和 CFF2 不因普通资源检查而自动获得替代程序。源内没有程序时，默认按对应入口的资源损失规则拒绝或跳过；页面渲染和光栅脱敏另可使用下文的显式映射。程序损坏、声明矛盾和实际缺字须失败，WARN／IGNORE 不绕过这些检查。传统 Type1 的子程序、复合字形和递归执行有独立累计预算；Type3 的实际程序绘制使用检查过的渲染器，不进入语义文字恢复。第三方解析器不提供硬内存沙箱。

## 渲染与脱敏的显式源字体映射

调用方可以按准确、区分大小写的 PDF BaseFont 名称，为受支持的无嵌入程序简单字体或水平 Type0 字体提供静态 TrueType 字节。名称不是页面资源键 `/F1`，也不按字体族或子集前缀模糊匹配。

```java
import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfFont;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.nio.file.Files;
import java.nio.file.Path;

var adapter = new PdfAdapter();
var input = adapter.input(Path.of("source.pdf"), "");
var font = PdfFont.of(Files.readAllBytes(Path.of("fonts/Explicit-Regular.ttf")));
var policy = ProcessingPolicy.builder()
        .feature("RENDER_FONT_MAPPING", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DEGRADE)
        .build();
adapter.render(input.allPages()).sourceFont("Helvetica", font)
        .dpi(144).policy(policy).prepare().toZip(Path.of("pages.zip"));
```

简单字体路径接受具有可确定源字宽的无程序 Type1／TrueType。名称型编码接受 StandardEncoding、WinAnsiEncoding、MacRomanEncoding；字典编码接受受支持 BaseEncoding 与合法 Differences，BaseEncoding 包括 Standard／WinAnsi／MacRoman／MacExpert。Differences 的起始码、名称序列及单字节范围须合法。缺少 Encoding 或字典 BaseEncoding 时，只有十二个准确标准拉丁字体名可用标准编码／AFM 例外；Arial 等别名不适用。非标准字体须有非符号 FontDescriptor 与明确 FirstChar／LastChar／Widths。Symbol／ZapfDingbats 不能靠默认符号编码自动映射，必须满足明确字典编码及字体声明条件。

水平 Type0 路径接受 Identity-H 或自定义内嵌一至二字节 CID CMap，要求 CIDSystemInfo、合法 W／DW 和可验证的 ToUnicode。每个参与映射的源码须对应一个合法 Unicode 标量，并能从显式 TrueType 的 cmap 得到无歧义的 CID→GID；生成确切 CIDToGIDMap。原 Encoding／ToUnicode、W／DW 和内容流保持，不把原 CID 当成新字体 GID。垂直 WMode、W2／DW2、命名非 Identity CMap、usecmap、超过二字节及多码点／歧义映射不通过此路径放行。

CMap 的每个映射必须完整位于声明码空间内；重叠范围或跨码长的前缀歧义拒绝，不能采用底层解析器的容错结果。合法且前缀不重叠的混合码长仍可接受，但相同整数码值因码长不同产生 CID／Unicode／GID 冲突时拒绝。每个 Type0 根绑定私有的映射后代字典，避免多个根共用源 CIDFont 时相互污染。

简单字体保留源 Encoding／Differences、ToUnicode、字宽和内容流；十二个标准拉丁字体缺少显式字宽时，使用固定依赖内 AFM。错误属性类型、矛盾符号标志、畸形字宽、无效 CMap 和实际缺字不能当作可接受的字体替代。

映射只影响本次操作中的文档，不修改输入字节或文件，不注册全局字体映射器，也不扫描系统字体。已有嵌入字体继续检查其真实程序，不用登记的替代字体掩盖损坏。请求在准备入口快照配置，`PdfFont` 在创建时快照字节。

源编码快照与实际加载的映射程序共同受 `maxTotalInputBytes` 限制，单字体及生成 CIDToGIDMap 受 `maxDecodedStreamBytes` 限制。CMap 解析、范围展开及跨字体的累计映射工作受 `maxContentOperators` 约束。映射后重新预检完整文档，源流、新增程序和字形映射共同计入解码、资源及对象额度，再开始绘制。操作内复用相同程序，登记数量受 `maxResources` 限制。

源步进保持不代表原字形外观保持；实际替代返回 `RENDER_FONT_MAPPING / DEGRADE / EXPLICIT_SOURCE_FONT_SUBSTITUTION`。默认 ERROR；WARN／IGNORE 使用相同替代路径，IGNORE 仍保留实际损失。没有匹配源字体的登记不产生替代损失。错误结构、损坏程序及实际缺字无条件失败。

`adapter.redact(input).sourceFont(name, font)` 使用相同映射后清除区域像素；仍必须明确接受全文光栅化损失，且所有源页均成功渲染才生成候选。文字替换与语义恢复没有此映射入口，仍要求各自的真实资源及表达子集。详见[初始映射决策](adr/0094-explicit-pdf-source-fonts-and-passive-graphics-state.md)与[兼容路径决策](adr/0096-pdf-compatibility-paths.md)。

## 各入口的结果

| 入口 | CFF 输入的实际处理 |
| --- | --- |
| [页面渲染](pdf-raster-api.md) | 使用嵌入的原字形轮廓生成 PNG／JPEG；实际绘制字形还检查嵌入状态、损坏状态及字形存在性 |
| [文字替换](pdf-text-editing-api.md) | 从原编码及 Unicode 映射匹配；未命中内容保留 CFF 字体，命中内容仍使用调用方显式 TrueType 字体并返回字体损失 |
| [光栅脱敏](pdf-redaction-api.md) | 渲染嵌入 CFF 后清除指定像素，新文件不携带源字体、文字对象或历史 |
| [语义恢复](pdf-semantic-import-api.md) | 恢复具有合法 Unicode、实际字形及受支持水平几何的可见文字；目标字体通过显式角色映射和导出配置选择，不直接把源字体程序放入语义模型 |

字体兼容与 [Form／图层／标签的兼容矩阵](pdf-passive-structures-api.md)分别检查，不将轮廓路径或未经验证的 ActualText 还原为正文。Type3 等可渲染而不可恢复的字形可通过 `PDF_STRUCTURE / DEGRADE` 成为整页流图片，不再是可编辑正文。新生成 PDF 的可见 CFF 正文仍采用既有轮廓输出；输入程序兼容不是新增原生 CFF 嵌入后端。

Type3 在创建底层字体对象前校验原始 Encoding／Differences 的类型、整数范围和顺序，字典不能伪装为流。每个 CharProc 必须以具有正确有限数值操作数的 `d0` 或 `d1` 开始，`d1` 的 BBox 不得倒置；后续重复声明、页或普通 Form 中出现这两个操作符均拒绝。实际字形和递归绘制仍受执行预算控制。

实际 Type1C、OTF／CFF、CIDFontType0C 输入、跨显示指令替换及四格式转换见[阶段七十六](verification/phase-76-pdf-font-compatibility.md)。

字典／CID 映射、传统 Type1、Type3、共享资源隔离及非法 CMap 的独立验收见[阶段九十三](verification/phase-93-pdf-compatibility-closure.md)。
