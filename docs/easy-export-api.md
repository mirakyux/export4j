# 配置一次，直接导出

当前开发版的 `export4j-starter` 提供 `Export4j` 统一入口。业务调用选择文本、Markdown、HTML 或 XML 模板，再指定 PDF／DOCX 目标。应用可以直接使用已安装字体，也可在初始化时设置字体、主题和公共图片。已发布的 `0.1.0-rc.3` 不包含此模块。

## 一个依赖与最小调用

只使用统一入口时，声明一个带明确版本的依赖即可；`<development-version>` 是本次实际构建的开发版本占位符：

```kotlin
dependencies {
    implementation("com.mirakyux.export4j:export4j-starter:<development-version>")
}
```

也可以在 Maven 中声明同一坐标，把 `export4j.version` 设为实际开发版本。可按[发布指南](releasing.md)构建本地 Maven 仓库。仅依赖 starter 无需单独声明 BOM。

```xml
<dependency>
  <groupId>com.mirakyux.export4j</groupId>
  <artifactId>export4j-starter</artifactId>
  <version>${export4j.version}</version>
</dependency>
```

```java
import com.mirakyux.export4j.Export4j;
import java.nio.file.Path;

try (var export = Export4j.builder().build()) {
    export.text("导出报告 / Export report").pdf(Path.of("report.pdf"));
    export.markdown("Report **summary**").docx(Path.of("report.docx"));
}
```

无参 `builder()` 在导出时检查运行机器上的几处标准字体路径。Windows 优先使用 Arial 和 Microsoft YaHei。macOS 优先尝试 Arial 和 Hiragino Sans GB。Linux 使用发行版已安装的 Liberation Sans、DejaVu Sans 或 Noto Sans，中文可用 Noto Sans CJK SC。实际字体取决于本机安装、格式和嵌入权限。精简容器可能没有字体包，PDF 遇到缺字会明确失败。需要跨机器复现版式时，使用下面的显式方式。

```java
import com.mirakyux.export4j.Export4j;
import com.mirakyux.export4j.FontFamily;
import com.mirakyux.export4j.font.FontSource;
import java.nio.file.Path;

var body = FontFamily.of("Liberation Sans",
        FontSource.file(Path.of("fonts/LiberationSans-Regular.ttf")));
try (var export = Export4j.builder(body).build()) {
    export.markdown("Hello $x^2$.").pdf(Path.of("output/report.pdf"));
    export.text("Literal **text** $x^2$.").docx(Path.of("output/literal.docx"));
}
```

输出父目录须事先创建。PDF 使用同一套物理字体测量和绘制，遵守字体嵌入限制。DOCX 声明字体族和样式，由阅读端选用实际字体。应用可长期复用 exporter 并在关闭时释放缓存。可用 `FontSource.resource(App.class, "/fonts/body.ttf")` 读取应用资源，或用 `FontSource.collection(path, postScriptName)` 按名称选择字体集合成员。

该模块传递引入现有数学模块及其已登记的 JLaTeXMath 依赖。需要更小的依赖集合时，继续按需使用原有模块；许可见[依赖记录](dependencies.md)。

## 中英文与全局样式

```java
import com.mirakyux.export4j.model.DocumentTheme;

var latin = FontFamily.builder("Liberation Sans",
        FontSource.file(Path.of("fonts/LiberationSans-Regular.ttf")))
        .bold(FontSource.file(Path.of("fonts/LiberationSans-Bold.ttf")))
        .italic(FontSource.file(Path.of("fonts/LiberationSans-Italic.ttf")))
        .boldItalic(FontSource.file(Path.of("fonts/LiberationSans-BoldItalic.ttf")))
        .build();
var chinese = FontFamily.of("LXGW WenKai TC",
        FontSource.file(Path.of("fonts/LXGWWenKaiTC-Regular.ttf")));
var theme = DocumentTheme.builder().rgb(0x223344)
        .bodyFormat(p -> p.fontSize(12).lineHeight(32).spaceAfter(8))
        .build();

try (var export = Export4j.builder(latin).eastAsia(chinese).theme(theme).build()) {
    export.markdown("中文正文与 formula $x + \\text{年}$.")
            .pdf(Path.of("output/report.pdf"));
    export.markdown("中文正文与 formula $x + \\text{年}$.")
            .docx(Path.of("output/report.docx"));
}
```

同一份族声明用于正文物理字体栈、公式 `\text{...}` 的显式文字字体和 Word 四脚本字体槽。首个族提供 `ascii`／`hAnsi`，`eastAsia(...)` 和 `complexScript(...)` 分别声明另两个槽；未声明时沿用首族。`fallbackFont(...)` 增加明确的物理候选，不扫描目录或字体名称，也不按物理字形覆盖逐字改写 DOCX 的字体槽。

PDF 不合成未提供的物理粗体、斜体或组合体；上例只提供中文 regular，不能据此承诺中文强调样式的物理字体。DOCX 输出已登记角色对应的字体族与粗斜体声明，实际绘制由阅读端处理。HTML 中的已登记 `font-family` 映射到对应族及变体角色，不把所有族压成同一默认角色。两种目标都继续拒绝未登记角色；主题中的具名角色必须与实例登记一致，不能传入任意名称后期待自动映射。

## 稳定的业务字体角色

`fontRole(name, family)` 为 Java 默认值、XML `role` 和 HTML `font-family` 登记同一个业务名称，不必使用内部 `family_N` 名称：

```java
import com.mirakyux.export4j.model.ContentOptions;

var report = FontFamily.builder("Liberation Serif",
        FontSource.file(Path.of("fonts/LiberationSerif-Regular.ttf")))
        .bold(FontSource.file(Path.of("fonts/LiberationSerif-Bold.ttf")))
        .build();
try (var export = Export4j.builder(latin).fontRole("report", report).build()) {
    export.markdown("Body and **bold**")
            .options(ContentOptions.builder().fontRole("report").build()).pdf(pdfPath);
    export.html("<p style='font-family:report'>Body and <strong>bold</strong></p>").docx(docxPath);
    export.xml("<document version='1'><p><span role='report_bold'>Bold</span></p></document>",
            java.util.Map.of()).pdf(pdfPath);
}
```

角色变体为 `report_bold`、`report_italic`、`report_boldItalic`，只登记实际提供的物理变体。Markdown／HTML 的强调沿当前已登记业务族选择变体；缺失变体在 PDF 和 DOCX 中都失败。普通 DOCX 文字仍只声明字体族与样式，不增加物理字形覆盖校验。主题、请求与 XML 的继承范围继续使用原有逐属性规则。

统一入口在解析前检查本次源的角色配置，避免全部强调的内容掩盖未登记的正文角色。Markdown／HTML 要求正文、六级标题和单元格的完整默认角色都可解析，即使某个语义域未出现在本次源中；字面文本只检查本次正文角色。XML 源块按实际词法作用域的配置检查，并继续使用同一累计预算会话；原生 XML 节点按实际模型角色检查。

业务族不会自动加入默认缺字候选栈，也不改写 Word 四脚本默认槽或公式文字的默认字体栈；需要默认候选时另外使用 `eastAsia`／`complexScript`／`fallbackFont`。角色及追加后缀后的名称须符合静态角色标识符规则；默认角色、`bold`／`italic`／`boldItalic`、`family_` 前缀、大小写别名冲突及重叠的角色／变体名称会被拒绝。角色别名与物理族名相同时，必须指向同一 `FontFamily` 实例。

## 业务调用与局部覆盖

每次调用创建独立请求；实例可复用，请求不能在线程间共享：

```java
import com.mirakyux.export4j.model.ContentOptions;

var result = export.markdown(markdownSource)
        .options(ContentOptions.builder().rgb(0x006688).spaceAfter(6).build())
        .pdf(pdfPath);
var count = result.formulas();
var changes = result.degradations();
```

全局 `defaults(ContentOptions)` 与请求 `options(ContentOptions)` 保留逐属性声明，未声明项继承；零、黑色和 `false` 是有效覆盖。Markdown、HTML 和 XML 分别接收主题的正文、六级标题、单元格及表格默认值，不用正文样式替换标题或单元格的独立配置。显式内容属性、XML 根／`scope`／块／行内声明以及 HTML CSS 按各自既有优先级覆盖对应默认值，未声明项继续继承。详见[源内容块](content-blocks-api.md)。

扩展解析器时，`ContentOptions.Resolved.styles()` 提供不可变的 `SemanticStyles`，其中包含六级 `SemanticStyle` 和单元格文字外观。原 8／9 参数构造器及访问器保留；旧构造器沿用正文、标题和单元格文字外观统一的历史语义，需要独立语义样式时使用主题解析结果。新增 `styles` 会改变 record 的组件列表，并参与值相等性；依赖反射组件或 record 解构形状的调用方需要适配。

| 入口 | 默认行为 |
| --- | --- |
| `text(source)` | 字面文本，默认关闭公式；不会把 `**...**` 或 HTML 当作格式 |
| `markdown(source)` | 明确选择 Markdown，公式默认启用，表格按继承配置处理 |
| `html(source)` | 明确选择离线 HTML 子集，公式默认启用 |
| `xml(template, data)` | 编译并绑定正文模板，源块格式由 XML／全局配置声明，不猜测数据格式 |

Markdown 内的原始 HTML 是独立开关，默认关闭，需要时显式 `rawHtml(true)`；普通 `html(...)` 不需要该开关。格式入口的字符串始终是内容，不是文件路径或 URL。

`image(key, InlineImage)` 可在全局登记公共图片，也可在请求上登记或覆盖精确资源键。图像字节和 pt 尺寸由调用方提供，库不会根据链接访问网络或本地文件。请求还支持 `metadata(...)`、`pageSettings(...)` 和 `execution(...)`，无需为这些选项重新创建全局实例。

## XML 模板与数据

```java
var xml = """
        <document version="1" content-format="MARKDOWN">
          <p id="greeting">Hello ${name}</p>
          <content id="detail" source="detail"/>
        </document>
        """;
export.xml(xml, java.util.Map.of("name", "Reader", "detail", "Result: $x^2$."))
        .pdf(pdfPath);
```

Java 调用也可以直接传入 record 或 JavaBean 实例，不必先转换为 JSON 或手写 Map：

```java
record GreetingData(String name, String detail) {}
export.xml(xml, new GreetingData("Reader", "Result: $x^2$."))
        .pdf(pdfPath);
```

根数据接受 Map、record 或 JavaBean，嵌套属性还可包含 List；它们在执行时进入同一份有界快照。record 读取组件，JavaBean 读取公开属性 getter，不读取字段；所有读取到的属性都须满足既有有限数据类型，未被模板引用的属性也会检查。null、日期、枚举、数组和 Set 不自动转换。getter 是可信应用代码；可见性、异常、循环引用及预算规则见 [Java 对象绑定](xml-template-api.md#java-对象绑定)。原有 Map 重载保持可用。

`<content source="..."/>` 将整个字段交给所选解析器；内容中的 `${...}` 不会再次插值。`fragment(key, xml)` 可登记既有安全模板片段，不增加磁盘 include 或外联权限。

同一 XML 结构需反复输出时，可显式持有纯语法计划：

```java
var syntax = export.prepareXml(xml);
export.xml(syntax, new GreetingData("First", "Result: $x^2$.")).pdf(firstPath);
export.xml(syntax, new GreetingData("Second", "Result: $y^2$.")).docx(secondPath);
```

`prepareXml` 只检查安全 XML、根／片段结构及解析／展开限额，不加载字体、不解析源内容或渲染公式，也不代表全部属性、表达式和资源语义已验证。计划保存准备时静态展开的片段、字符串与预算计数，用户决定其生命周期；没有全局模板缓存。每次执行仍按当前主题、资源与配置创建新编译器和内容会话，重新检查解析／展开限额并快照数据。计划不捕获本次 `MathContentSource` 或运行期 `DocumentTemplate`，更换执行实例不会改写计划中的片段。底层 `prepare`／`compile` 的完整规则见[模板语法复用](xml-template-api.md#复用已解析语法)。

原有字符串重载保留。新增语法计划重载后，`xml(null, data)` 无法自动判定源类型；需要测试空值拒绝时，使用 `xml((String) null, data)` 或显式 `XmlTemplate.Syntax` 类型。合法的非 null 字符串调用保持可用。

同一个 `xml(...)` 入口支持[分节、页眉和页脚](xml-sections-api.md)。PDF 也支持 XML 脚注和尾注。DOCX 遇到脚注或尾注会明确拒绝。数据在执行时由模板绑定路径取快照，执行期间不得并发修改输入数据。

## 目标与严格失败

PDF 只编译视觉目标，DOCX 只编译原生 Word 目标。PDF 可绘制但 Word 未支持的公式，不再使 PDF 请求因无关的原生子集限制失败。每次目标执行独立编译，不承诺两次导出共用同一个中间文档。

公式候选列表默认为空。不支持的语法、无法读取的字体／资源、未登记角色和不支持的目标结构会失败；PDF 缺字仍严格失败。DOCX 阅读端缺少字体不属于导出时的物理字形校验。没有自动转图片或转文字，现有预算保持不变。需要公式图片候选时，在初始化时明确声明：

```java
import com.mirakyux.export4j.math.MathContent;

var export = Export4j.builder(latin).eastAsia(chinese)
        .formulaFallbacks(java.util.List.of(MathContent.Fallback.IMAGE))
        .build();
```

实际表示变更通过 `Result.degradations()` 返回，包含公式出现索引、目标、候选和原因；`formulas()` 返回出现次数。预算、取消和超时按契约不得降级。DOCX 默认使用标准四脚本字体槽和对象 `atLeast` 行距，生产写出由 Java 完成，不调用 Word、LibreOffice 或打印服务。公式与格式子集见[混合内容 API](rich-content-api.md)及[本地修复验收](verification/easy-export.md#2026-11-19-修复结果)。

## 生命周期、预算与输出

实例默认拥有有界、空闲过期的 `FontFaceCache`，可用 `cacheStats()`／`clearCache()` 观察和清理。`fontCache(cache)` 借用外部缓存，实例关闭时不关闭它；所有者负责关闭。`FontSource` 同一实例在一次操作中只加载一次，短生命周期引擎与编译结果不被全局实例保存。公式文字字节只在实际需要文字组时准备，不为普通字面导出预先建立数学文字字体快照。

到期只解除缓存引用，不使在用字体失效，也不保证 RSS 立即下降。调用方提供的字节源仍持有其编码快照。缓存边界继续见[字体缓存](font-cache-api.md)。实例可并发调用的前提是提供的字体来源和执行回调也支持对应并发方式。

`pdfBackend(...)` 使已有 PDF 存储策略、后端预算和解析缓存从统一入口可达：

```java
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.pdf.PdfFontCache;
import com.mirakyux.export4j.backend.pdf.PdfOptions;

try (var programs = PdfFontCache.builder().maxEntries(4).build()) {
    var backend = PdfBackend.builder().fontCache(programs)
            .options(PdfOptions.storage(PdfOptions.Storage.TEMP_FILE)).build();
    try (var export = Export4j.builder(latin).pdfBackend(backend).build()) {
        export.text("Body").pdf(pdfPath);
    }
}
```

实例借用不可变 `PdfBackend`，每次输出仍创建独立 PDF 会话和文档。关闭 exporter 不关闭调用方的 `PdfFontCache`，由缓存所有者关闭。`TEMP_FILE` 仅将 PDF 流缓冲放入临时存储，不表示布局或完整 PDF 对象树为恒定内存；DOCX 不使用该后端配置。

默认已配置解析、公式和布局的结构预算；默认没有超时或实际输出字节上限。可以在全局或单次请求上设置：

```java
import com.mirakyux.export4j.model.ExecutionOptions;
import java.time.Duration;

var controls = ExecutionOptions.builder().timeout(Duration.ofSeconds(30))
        .maxOutputBytes(16L * 1024 * 1024).build();
export.markdown(markdownSource).execution(controls).pdf(pdfPath);
```

超时和取消采用同步合作式检查，不是恶意输入的强制内存沙箱。路径输出在同一父目录写临时文件，最终检查成功后原子替换；失败保留旧目标，文件系统不支持原子移动时明确失败。流输出保持借用，库不关闭调用方的流；失败时流可能已有部分内容，不提供回滚。

完整公开样例见 [`EasyExportExample`](../examples/src/main/java/com/mirakyux/export4j/examples/EasyExportExample.java)，隔离制品调用方见 [`EasyExportConsumer`](../verification/consumer/src/main/java/consumer/EasyExportConsumer.java)。设计与本批结果分别见 [ADR-0118](adr/0118-configured-export-entry.md)和[统一入口验收](verification/easy-export.md)。

稳定字体角色、后端借用及 XML 语法复用的制品证据另见[架构优化验收](verification/architecture-convergence.md)。
