# 配置一次，直接导出

当前开发版的 `export4j-starter` 提供 `Export4j` 统一入口。字体、全局样式和公共图片在应用初始化时声明一次；业务调用选择文本、Markdown、HTML 或正文 XML 模板，再指定 PDF／DOCX 目标。使用端不必另外装配解析器、公式处理器、布局引擎和 DOCX 适配器。已发布的 `0.1.0-rc.3` 不包含此模块。

## 一个依赖与最小调用

只使用统一入口时，声明一个带明确版本的依赖即可；`<development-version>` 是本次实际构建的开发版本占位符：

```kotlin
dependencies {
    implementation("com.mirakyux.export4j:export4j-starter:<development-version>")
}
```

同时直接使用其他 Export4j 模块时，可以另外引入同版本 BOM 统一版本。仅依赖 starter 的两种元数据模式消费见[本批验收](verification/easy-export.md)。

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

当前入口仍读取声明的字体源，示例中的字体文件须有效，输出父目录须事先创建。PDF 使用显式物理字体并严格检查所需字形。DOCX 的普通正文与列表标记保留文字、字体族和样式声明，不因本地物理字体缺少这些字形而拒绝；阅读端是否安装字体、如何处理缺失字体或字形，由其所在系统决定。库不扫描系统字体，也不在 DOCX 中嵌入字体。生产服务可以长期复用 `export`，在应用关闭时再调用 `close()`，不必每次请求重新创建。

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

`<content source="..."/>` 将整个字段交给所选解析器；内容中的 `${...}` 不会再次插值。`fragment(key, xml)` 可登记既有安全模板片段，不增加磁盘 include 或外联权限。

此统一入口当前只处理正文 XML。页眉／页脚、分节和注释等尚未被本入口的公式编译投影覆盖，仍明确拒绝，不会删除后继续导出。需要这些模型能力时，继续使用原有 [XML 模板](xml-template-api.md)和[文档引擎](fluent-api.md)入口；不能把本入口称为全部 XML 能力的替代品。数据借用到执行时，由模板绑定路径取快照；不要在执行期间并发修改输入数据。

## 目标与严格失败

PDF 只编译视觉目标，DOCX 只编译原生 Word 目标。PDF 可绘制但 Word 未支持的公式，不再使 PDF 请求因无关的原生子集限制失败。每次目标执行独立编译，不承诺两次导出共用同一个中间文档。

公式候选列表默认为空。不支持的语法、无法读取的字体／资源、未登记角色和不支持的目标结构会失败；PDF 缺字仍严格失败。DOCX 阅读端缺少字体不属于导出时的物理字形校验。没有自动转图片或转文字，现有预算保持不变。需要公式图片候选时，在初始化时明确声明：

```java
import com.mirakyux.export4j.math.MathContent;

var export = Export4j.builder(latin).eastAsia(chinese)
        .formulaFallbacks(java.util.List.of(MathContent.Fallback.IMAGE))
        .build();
```

实际表示变更通过 `Result.degradations()` 返回，包含公式出现索引、目标、候选和原因；`formulas()` 返回出现次数。预算、取消和超时按契约不得降级。当前开发版仍有公式文字字体合计超限被错误归类、显式 `TEXT` 候选可吞掉该错误的问题，默认无候选时仍严格失败，见[已知限制](verification/easy-export.md#已知限制)。DOCX 默认使用标准四脚本字体槽和对象 `atLeast` 行距，生产写出由 Java 完成，不调用 Word、LibreOffice 或打印服务。公式与格式子集见[混合内容 API](rich-content-api.md)。

## 生命周期、预算与输出

实例默认拥有有界、空闲过期的 `FontFaceCache`，可用 `cacheStats()`／`clearCache()` 观察和清理。`fontCache(cache)` 借用外部缓存，实例关闭时不关闭它；所有者负责关闭。`FontSource` 同一实例在一次操作中只加载一次，短生命周期引擎与编译结果不被全局实例保存。公式文字字节只在实际需要文字组时准备，不为普通字面导出预先建立数学文字字体快照。

到期只解除缓存引用，不使在用字体失效，也不保证 RSS 立即下降。调用方提供的字节源仍持有其编码快照。缓存边界继续见[字体缓存](font-cache-api.md)。实例可并发调用的前提是提供的字体来源和执行回调也支持对应并发方式。

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
