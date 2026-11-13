# DOCX 原生数学公式 API

`export4j-adapter-docx` 用不可变 `DocxMath` 类型树读取、构造和修改 Word 原生 OMML 公式。`writeMath` 生成标准 `m:oMath`／`m:oMathPara`，公式保持可编辑；模型不保存原包、XML 片段、图片或 TeX 文本来代替公式结构。

公式通过 `DocxMathDocument` 包裹现有 `DocxSemanticDocument`。公共核心模型、布局引擎和 PDF／SVG 后端不依赖这一适配器类型。该适配器没有通用 TeX↔OMML、MathML 或原生数学模型到 PDF 的转换入口；受限 TeX→OMML 和 Markdown 公式识别由可选[公式模块](math-api.md)提供。

## 读取、构造和修改

| 入口 | 结果与所有权 |
| --- | --- |
| `readMath(input, policy)` | 返回 `MathReadResult(document, diagnostics)`，采用默认数学预算；借用输入流 |
| `readMath(input, policy, mathLimits)` | 同时应用显式数学预算、`DocxLimits` 与模型结构约束 |
| `writeMath(document, output)` | 从有类型模型重建完整标准包；借用输出流 |
| `writeMath(document, output, mathLimits)` | 在同一输出契约下应用显式数学预算 |
| `document.withEquation(id, expression)` | 替换指定公式，返回新文档；原文档不变，未知 ID 失败 |

以下完整示例创建可编辑分式，严格回读后把分母从 `2a` 改为 `3a`。矩阵与分式组合的发布制品调用方见 [NativeMathExample](../verification/consumer/src/main/java/consumer/NativeMathExample.java)。

```java
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.docx.DocxMath;
import com.mirakyux.export4j.adapter.docx.DocxMathDocument;
import com.mirakyux.export4j.adapter.docx.DocxSemanticDocument;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class NativeFraction {
    public static void main(String[] args) throws IOException {
        var body = new SectionedDocument(DocumentMetadata.EMPTY,
                List.of(new Section("main", PageSettings.A4,
                        List.of(new Paragraph("formula-paragraph", "\ufffc",
                                ParagraphFormat.of(18))), 0)));
        var equation = new DocxMathDocument.Equation("fraction",
                new DocxSemanticDocument.Point("formula-paragraph", 0),
                DocxMathDocument.Placement.DISPLAY_CENTER,
                new DocxMath.Fraction(DocxMath.run("1"), DocxMath.run("2a")));
        var document = new DocxMathDocument(body, List.of(equation));
        var adapter = new DocxAdapter();
        Files.createDirectories(Path.of("build"));
        var source = Path.of("build/native-fraction.docx");
        try (var output = Files.newOutputStream(source)) {
            adapter.writeMath(document, output);
        }
        DocxAdapter.MathReadResult imported;
        try (var input = Files.newInputStream(source)) {
            imported = adapter.readMath(input, DocxAdapter.Policy.STRICT);
        }
        var changed = imported.document().withEquation("fraction",
                new DocxMath.Fraction(DocxMath.run("1"), DocxMath.run("3a")));
        try (var output = Files.newOutputStream(Path.of("build/native-fraction-edited.docx"))) {
            adapter.writeMath(changed, output);
        }
    }
}
```

`DocxMathDocument` 会展开嵌套 `Sequence`、归一化单子节点序列，并按语义段落顺序和 UTF-16 位置排列公式。未携带自有 ID 的标准 OMML 也可读取；导入时为公式及宿主段落生成 ID，不要求 Export4j 扩展元数据。

## 类型树与标准节点

| `DocxMath` 节点 | 语义与实际 OMML |
| --- | --- |
| `Run`、`Sequence` | 数学文字运行与有序子树；`m:r`／`m:t` |
| `Fraction` | 带横线分式，`m:f` 的分子／分母；不包含线性或无横线分式 |
| `Subscript`、`Superscript`、`SubSuperscript` | 下标、上标、上下标；`m:sSub`／`m:sSup`／`m:sSubSup` |
| `Radical` | 平方根或显式根指数，`m:rad`；缺省指数用标准 `degHide` 声明 |
| `Nary` | 积分、二重积分、闭路积分、求和、乘积；`m:nary`，含上下限、限位和增长声明 |
| `Matrix` | 矩形行列、逐列左／中／右对齐及上／中／下基线；`m:m`／`m:mr`／`m:e` |
| `Delimiter` | 开／闭／分隔字符、有序参数、增长与形状；`m:d`，可表达分段函数的单侧括号 |
| `Function` | 函数名子树与参数，`m:func`；函数名本身可含上标 |
| `LowerLimit`、`UpperLimit` | 基底和限位表达式，`m:limLow`／`m:limUpp` |

`Run` 使用有效 Unicode，拒绝控制字符及孤立代理项。样式为斜体、正体、粗体、粗斜体或普通文字。`fontSize=0` 继承宿主段落，否则单位为 pt，必须是半 pt 的倍数且不超过 144 pt；`rgb=0` 使用默认颜色，其他值为 24 位 RGB。

数学节点／深度预算按实际类型树计算。平方根隐藏的空根指数、运算符隐藏的空上下限属于标准 XML 占位，不计作模型节点；可见的空序列仍计费。隐藏参数必须为空，非空内容或未知属性仍被拒绝，且所有 XML 都受独立包预算约束。

数学运行只读取已映射格式。运行的显式字体及 Word 写入的结构控制字体，只有与文档 `mathFont` 完全相同时才规范化为该默认字体；其他控制格式、未知节点／属性、没有模型映射的语法均拒绝。隐藏根指数或上下限中若仍有非空内容，也会失败，不通过隐藏标志丢弃内容。

## 锚点与设置

每个 `Equation` 占宿主普通段落中一个真实 `U+FFFC`，锚点为 `DocxSemanticDocument.Point(paragraphId, utf16)`。偏移使用原文 UTF-16；emoji 占两个 UTF-16 单元。每个标记只能绑定一个公式，不能同时是图片或字段；段落中未绑定的对象标记也失败。

`INLINE` 可与普通正文共处一段；`DISPLAY_LEFT`、`DISPLAY_CENTER`、`DISPLAY_RIGHT`、`DISPLAY_CENTER_GROUP` 要求段落文字恰好为该单个标记。标记必须使用宿主段落的默认普通运行样式，不能带超链接。公式不能覆盖修订、内容控件或目录缓存范围。原有语义对象仍遵守各自契约，不通过数学入口放宽。

导入先把每个标准公式映射为一个标记，再由既有语义读取器处理正文。复杂字段的旧缓存可能从多个字符归一为一个字段标记；数学锚点随后按实际保留的对象槽重新定位，不沿用归一前的缓存长度。

`DocxMathSettings` 保存标准 `m:mathPr`：明确的字体族、二元运算符换行／减号规则、小分式与显示默认值、页边及段间距、默认公式对齐、换行缩进与方向，以及积分／其他大型运算符的默认限位。整数距离均为 twip，`20 twip = 1 pt`，范围为 `0..31680`。默认字体声明为 `Cambria Math`；适配器不扫描、加载或渲染该系统字体，具体显示由 Word 决定。

## 预算、诊断与失败

默认 `DocxMathLimits` 是每次完整导入／输出累计 10,000 个公式、100,000 个数学节点、深度 32、100,000 个矩阵格和 4,000,000 个文字码点。调用方可通过显式限制收紧预算；`DocxMathDocument` 的默认模型上限仍适用。单个矩阵还要求非空矩形、每边最多 1,024、最多 100,000 格。数学节点、文字和矩阵格同时计入共享 DOCX 来源预算，不能借独立预算绕过 `DocxLimits`。

未知或畸形 OMML 在 `STRICT` 和 `ALLOW_LOSS` 下均直接失败。`ALLOW_LOSS` 只沿用普通 Word 正文、样式、部件和元数据的已实现损失通道，并返回每条实际诊断；不把未知公式转成文字或图片。普通 `read`、`readSemantic` 及经它们导入的转换入口无法表示数学类型树，因此遇到原生公式均要求改用 `readMath`，即使选择 `ALLOW_LOSS` 也不会静默丢式。全包拒绝检查在样式物化和侧边正文转换之前执行；`settings.xml` 仅豁免标准 `mathPr` 设置叶子。

借用输入／输出流不会被关闭。数学模型、完整包和全部编码在触碰输出流之前验证；预算、结构错误及同步取消不会提前写出部分包。最终 `output.write(...)` 的 I/O 失败仍可能留下部分字节，调用方需要原子文件发布时应先写临时文件，再在成功后移动。包、关系、XML、宏／活动对象、外部资源及字段安全限制与[原生语义入口](docx-semantic-api.md)相同，损失策略不能绕过。

## 示例与独立验证

从仓库根目录运行；Linux／macOS 将 `.\gradlew.bat` 替换为 `./gradlew`。实际 Word 验收只在安装 Microsoft Word 的 Windows 环境执行。

```powershell
.\gradlew.bat :examples:manuWeaveMath
python scripts/verify_manuweave_math.py

& verification/verify_docx_math_word.ps1 `
    -InputPath examples/build/manuweave-math/math.docx `
    -OutputDirectory build/verification/manuweave-math-word
.\gradlew.bat :examples:manuWeaveMath `
    --args="--verify-word-saved build/verification/manuweave-math-word/word-saved.docx"
python scripts/verify_manuweave_math.py `
    --word-directory build/verification/manuweave-math-word
```

样例的 8 个公式分别验证分式／根式、上下标、积分、求和、矩阵、分段函数、希腊字母／三角函数、极限／乘积。三个标准包轮次会移除所有自有命名空间及属性；独立脚本比较标准 OMML，检查公式修改仅影响首分母。可选发布制品验证使用隔离项目的 `nativeMath` 任务，再把本次构建目录传给脚本的 `--consumer-build-directory`。未显式指定 Word 或调用方目录时，对应报告为 `not_requested`，不能当作通过。

PDF／SVG 示例单独在 `examples` 内使用 JLaTeXMath 1.0.7 和明确的字体资源生成矢量轮廓，保留原 TeX 为 PDF ActualText／SVG aria-label。该依赖不进入公共 DOCX 适配器；同一示例的原生公式类型树由 Java 独立构造，没有通用 TeX↔OMML 转换承诺。

当前实际 Word 16.0、build `16.0.20527` 应用自动化完成了 8 个原生公式的打开、首分母 `2a→5a` 编辑、保存、重开和 PDF 导出；其他 7 个公式保持不变。Word 保存文件的普通样式、主题／注释部件和元数据在导入时产生 75 条明确诊断，数学类型树及设置在重新编码后严格回读相等。普通 Word 原文件仍不满足整体严格导入，不能据此宣称任意 Word 无损往返。

独立脚本用 pypdf 检查结构和逻辑文本，用 Poppler／PDFium 按 192 DPI 全页渲染，采用项目既有 blur 2 px、平均误差 `<4`、变化像素比例 `<0.06` 协议，并生成逐页图片和 contact sheet。这些是应用自动化与独立输出证据，不是人工 Word UI 验收；Word 不进入库的生产调用链。结果与边界见[ManuWeave 样例验收](verification/manuweave-templates.md)和 [ADR-0108](adr/0108-native-docx-math-and-example-typesetting.md)。
