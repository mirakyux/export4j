# 文献 Markdown 与 PDF 接入

MinerU 文献的 Markdown、HTML 合并表格、本地图片及 TeX 应分别处理。当前可复用路径为：调用方将 CommonMark／GFM 显式转换成 HTML，使用 `HtmlImporter` 严格导入，再通过公共模型设置列宽和图文分页，最后交给 `DocumentEngine`。这些步骤均可离线运行。

## 混合 HTML 表格

`MarkdownImporter` 接受 GFM 管道表格，但不解释原始 HTML 表格。严格模式报 `RAW_HTML`；DEGRADE 保留字面 HTML，DISCARD 删除对应节点，均不等于保留表格语义。

以下桥接由调用方显式依赖 CommonMark、GFM tables 和 strikethrough 扩展。解析前仍需按输入来源限制字符串大小及解析预算；HTML 导入的预算不能代替前一阶段的限制。

```java
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import com.mirakyux.export4j.adapter.web.HtmlImporter;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.InlineImage;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.ParagraphFormat;

static Document importLiterature(String markdown, Map<String, InlineImage> images,
        PageSettings page, ParagraphFormat body) throws IOException {
    if (markdown.length() > 1_000_000) {
        throw new IllegalArgumentException("Markdown exceeds the input budget");
    }
    var extensions = List.of(TablesExtension.create(), StrikethroughExtension.create());
    var parser = Parser.builder().extensions(extensions).build();
    var renderer = HtmlRenderer.builder().extensions(extensions).build();
    String html = renderer.render(parser.parse(markdown));
    var builder = HtmlImporter.builder().pageSettings(page).paragraphFormat(body);
    images.forEach(builder::image);
    var result = builder.build().read(html).execute();
    if (!result.report().losses().isEmpty()) {
        throw new IllegalArgumentException("Unexpected HTML import losses");
    }
    return result.document();
}
```

表格应有真实 `<thead>` 或连续的全 `<th>` 前缀，且至少有一行正文。跨行表头的全部物理行须包含在表头区内，不能只把第一行标为表头。保持原有 `rowspan`／`colspan` 和源顺序，不按提取文本重新拼接格网。未知结构或格式仍需逐项处理损失，不通过关闭全部诊断来接受。

该桥接的损失位置以生成的 HTML 为准。调用方需要回到 MinerU 来源定位时，应另存原始输入与转换映射；不能把 HTML 偏移冒充 Markdown 偏移。

## 显式资源、列宽和图注

图片由调用方读取并登记精确 `src`、编码和 pt 尺寸。读取本地资源时，将规范化路径限制在受信任资源目录内；不根据文档中的 URL 自动下载。大图可按可用内容宽高等比计算尺寸，固定行距至少容纳图片高度及基线偏移。JPEG 经过处理后的输出不承诺字节或像素无损。

HTML 语义导入默认使用等比例表格列。需要按内容分配时，将导入的 `Table` 列声明改为有界 `ColumnWidth.Auto`，并保留行、跨度和表头。只有 Fixed／Auto 的表会为不可拆分图片／字段／矢量保留下限；最大列宽和页面宽度仍须足够，含 Fraction 的策略见[自动列约束](automatic-merged-columns-api.md)。

`ParagraphFormat.pagination().keepWithNext()` 可以将图前说明与下一图片段落关联；图片段落使用 `keepTogether(true)`。如果图注在图后，则将图片段落的 `keepWithNext` 设为 true。按业务数据明确识别图和图注，不用“所有短段落都跟下一块”推断正文结构。整个组合超出新页时仍须显式调整尺寸或结构。

## 数学和文本提取

高层 [InlineVector／VectorResource](inline-vectors-api.md) 接受调用方已经生成的不可变有界轮廓，进入 `Paragraph.mixed`／`TableCell.mixed`。`HtmlImporter.Builder.vector(src, value)` 与 `MarkdownImporter.Builder.vector(destination, value)` 将现有图片占位符精确映射为矢量，不需要把公式转成 PNG。库不内置 TeX 解析器；调用方仍需限制 TeX 子集、解析工作量、路径数量、字体、尺寸和来源记录。既有高分辨率 PNG＋`InlineImage` 仍可使用，图片 `alt` 不会自动成为 PDF 可搜索公式。

调用方可复用自己的公式轮廓转换器，将其结果显式变成 `VectorResource` 的 MOVE／LINE／CURVE／CLOSE 段，坐标对齐基线，以原 TeX 作为 `InlineVector.logicalText`。每个公式只占一个原始 UTF-16 标记；动态公式必须整体重建，不能只替换 TeX 而复用旧轮廓。HTML 中的显式宽高必须与资源 pt 尺寸一致。独立组件仍可使用 `DrawCommand.OutlineText`，现有 [ManuWeave 数学示例](../examples/src/main/java/com/mirakyux/export4j/examples/ManuWeaveMath.java)属于受限 TeX 实现；高层矢量入口不代表任意 TeX、自动 TeX↔OMML 或通用 SVG 导入。

[InlineVectorsExample](../examples/src/main/java/com/mirakyux/export4j/examples/InlineVectorsExample.java)复用这一示例转换器，在段落、表格和 HTML 图片占位符中登记公式资源。运行 `:examples:inlineVectors` 后执行 `python scripts/verify_inline_vectors.py`，可复核 1 页、3 个资源的 8 次真实矢量呈现及 PDFium 原 TeX 搜索／选区；没有增加生产 TeX 解析依赖。其结构、四种渲染结果和负控制见[矢量验收记录](verification/inline-vectors.md)，不替代真实文献的验收。

`ActualText` 保留、阅读器搜索和提取器逐字坐标是不同能力。矢量公式用 ActualText 保存原 TeX；已有八式组件样张在 PDFium 中可逐式搜索原文，但 pypdf 普通提取只返回轮廓的不可见锚点。原生文字的改进限于仅含图片、LTR 静态 TTF、无装饰且实际字形一一映射的安全混排行；含字段／矢量及复杂塑形仍保留原轮廓路径，见[行内图像](inline-images-api.md)。不能把公式逻辑文字当作原生字形，或叠加整层隐藏正文掩盖工具差异。

DOCX 和 Web 导出没有通用矢量映射，默认拒绝；只有明确设置 `INLINE_VECTOR`／DEGRADE 才保留逻辑文字并报告 `VECTOR_REPLACED_WITH_LOGICAL_TEXT`。XML v1 保存对所有策略均拒绝矢量。消费侧采用新入口时应重新编译调用方，逐项核对新损失报告，并对真实文献重新执行结构、渲染和提取检查；仅替换依赖或登记一个键不能代替完整验收。

PDFium 还会将部分行末连字符返回为 U+FFFE，并将 `FPDFText_IsHyphen` 标为 1；若其他工具可还原连字符、`FPDFText_HasUnicodeMapError` 为 0，应先确认提取器行为。内容比较可以单独记录这种标记，不能直接改写 PDF 字符映射。

## 字体复用与内存

同一个字体程序用于多个角色时，先加载一次 `FontFace`，再将该实例传入各角色的显式字体栈；粗体或斜体仍使用实际变体，不合成字形。重复调用 `TrueTypeFontLoader.load` 会创建独立的字节快照和 Unicode 指标映射，即使 SHA-256 相同，也不会自动合并这些加载结果。

JDK 物理字体加载使用 `FontData` 的只读视图，省去传入前的完整字节克隆。字体加载入口仍保持防御复制。生产依赖仅使用未修改的官方 FontBox 3.0.8，源码内嵌、补丁和可选构建开关均已移除；正常构建不依赖实验目录。这些已有优化没有移除官方内部完整字体和字形表快照，也不改变文字、公式或图片质量。

曾评估的共享 `glyf` 视图可减少解析分配，但独立保留字形表可能延长整个快照生命周期，且中文进程峰值没有稳定下降，因此没有采用。`TrueTypeFont.close()` 对堆快照不等于立即回收，仍需释放引用；不使用跨会话无界缓存、反射清表或生产 `System.gc()` 强行制造下降。回退验收不包含新的性能测量，不宣称内存减半或固定倍率收益。

源字体字节预算、解析时堆占用和进程工作集是三个不同指标。容量测量应固定字体角色、文档和图片质量，分别记录首次与预热后的导出，并使用多个独立 JVM；不能从一次 OOM 推断普遍最低堆容量，也不能把特定样本在 128 MiB 堆内完成视为生产容量保证。

## 本地验证范围

### 新增入口与本地制品

新增高层矢量样例已完成独立结构、坐标、提取和全部页面渲染检查。既有行内图像验证也重新检查了 4 页图文混排与 4 页 DOCX 往返样例；这些结果限于明确的样例和安全原生文字子集，不代表所有混排均使用原生字体。两篇真实文献的新原生混排路径与历史回退基线分别记录如下。

生产、HTML／Markdown 与能力样例检查通过。本地分发覆盖 16 个库及 BOM，新隔离 Gradle 与真实 Maven 调用方均通过；Maven 按实际 POM／BOM 解析 31 个运行时 JAR，其中 29 个 Java JAR 和 2 个显式 native JAR。48 个自研二进制／源码／Javadoc JAR 未包含内嵌 FontBox，官方 FontBox 的 192 个类仅有一个提供者，实际加载来源与分发制品一致。临时本地签名验证覆盖 82 项制品，并验证篡改拒绝；这些都是本地构建和消费证据，不代表远程发布。

Maven 调用方还通过公开坐标编译并绘制了 28 段分式资源，保留一枚原子标记及原 TeX，无图片，路径坐标与声明一致；PDFium 搜索／选区、144 DPI 双引擎渲染及整页复查通过。该最小调用方与上面的 8 次呈现综合样例分别验收。

### 历史回退基线

历史列宽修复复验保留了原输入快照，移除了桥接中扫描图片并手动抬高 `Auto.min` 的逻辑，生成中文 6 页、英文 11 页，包含 10 张表、26 个合并单元格、29 处公式图片和 1 张原图。英文图注与图同在第 6 页，严格 HTML 导入损失及缺字替换警告均为 0；显式中文字体回退 INFO 单独记录。

该历史输出中，英文 247 个可见 HTML 文本节点可由 pypdf 全部找到；中文需联合检查真实 ActualText 才能覆盖 197 个节点。这是原轮廓混排样张的结果，不是新安全原生文字路径的验收数字，也不证明任意提取器的阅读顺序一致。

官方 FontBox 完整回退后，使用实际本地分发 JAR 重新生成同样 17 页，核对文字／ActualText、字体程序、图像及几何；Poppler／PDFium 各自对两组既有基线共做 68 次全页比较，像素全等。双引擎全部 17 页联系表逐页复查，未见新增截断或错位。证据为本地 `build/verification/fontbox-rollback-official-20261112/literature-formal/verification.json`；这是回退和依赖身份验收，不包含新增 InlineVector 或原生混排路径的最终真实文献结果。原始文献及字体不纳入公共仓库。

### 本次真实文献

本次从新的隔离调用方依赖和实际分发制品生成中文 6 页、英文 11 页。输入快照、10 张表、26 个合并单元格、29 处公式 PNG 和 1 张原图保持不变；这 29 处公式没有被转换成矢量，新增高层矢量由独立样例验证。运行 JAR、SBOM、本地仓库及调用方之间核对摘要，官方 FontBox 为唯一提供者。

PDF 内容流的全文逻辑字符及图片标记顺序严格保持一致。27,077 个稳定字符框与原坐标完全相同，转为原生输出的 9 个字符改为真实字形框，原 ActualText 整行锚点框不作为逐字墨迹基线。pypdf、pdfplumber 和 PDFium 的普通提取均能恢复“年龄”和“DN病程”。同时逐项检查 3,416 处原 HTML 空白分隔边界，其中中文 194 处、英文 3,222 处，均仍有提取分隔符；PDFium 对字体空格的 generated 标记不能单独证明源空白是否存在。

PDFium 仅在中文第 2 页合并表头出现明确的顺序变化：旧输出将“男”“女”穿插到首层表头中，新输出先提取包含“年龄”“DN病程”的首层，再提取下一层“男”“女”，与原 HTML 的行层级一致。验证仅接受该精确表头置换，其他页面的非分隔文字和顺序继续严格比较；不宣称原始空白数量或整篇 PDFium 原始字符串完全相同。

17 页在 Poppler／PDFium 中共做 34 次同引擎比较，其中 32 次全页像素完全相同。剩余 2 次均为中文第 2 页，差异限定在这 9 个真实原生字形区域；Poppler 另有旧零面积空格轮廓留下的 1 个淡像素，从 RGB 251 变为 255。该点由旧路径和坐标单独确认，仅允许精确源点及最多 4 灰阶的差值，相邻像素或更大差异仍失败。它不代表全页像素相同，也没有通过整体放宽差异阈值接受。

全部双引擎联系表、中文第 2 页双引擎整页及全部差异／蒙版已经实际复查，未见新增文字截断、表格位移或缺图。新的独立语义分式页也完成路径、ActualText、外链、双引擎渲染及整页复查。完整本地证据位于 `build/verification/literature-engine-fixes-20261113/attempt02/verification.json` 与同目录 `acceptance-summary.json`，私有样本和原始证据不纳入公共仓库。

本次没有重新测量分配、耗时、堆、RSS 或容量，输出验收不能推导新的性能收益或内存保证。
