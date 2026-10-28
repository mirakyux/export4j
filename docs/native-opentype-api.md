# 显式原生 OpenType 与 CFF 嵌入

`export4j-opentype` 在排版阶段完成 GSUB／GPOS、可变轴插值、CFF2 轮廓和字体着色，并输出后端中立绘制指令。通用竖排使用真实 TTB 度量及 `vert`／`vrt2` 替换，按列换行、分页，可显式组合纵中横。PDF／SVG／PNG 后端消费已确定的轮廓与彩色图像，不再次塑形。

普通 Java 核心保持现有入口；新增模块和平台二进制均由应用显式选择。它与组件模块已有 `OpenTypeText` 不同，使用时需注意完整包名 `com.mirakyux.export4j.opentype.OpenTypeText`。

## 依赖及平台

~~~kotlin
dependencies {
    implementation(platform("com.mirakyux.export4j:export4j-bom:0.1.0-SNAPSHOT"))
    implementation("com.mirakyux.export4j:export4j-opentype")
    implementation("com.mirakyux.export4j:export4j-backend-pdf")
    runtimeOnly("org.lwjgl:lwjgl:3.4.3:natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-harfbuzz:3.4.3:natives-windows")
}
~~~

两份原生 classifier 必须一致，版本与绑定保持 3.4.3。实际 Windows x64 包内 HarfBuzz 为 14.3.0，`OpenTypeFont.nativeVersion()` 返回运行库版本，不返回 Java 头文件版本。实现绕过该版本与绑定不同的 raster convenience-glyph ABI，通过稳定 core paint 回调接入，并按运行版本选择 `get_funcs` 签名。

上游同时发布 `natives-windows-arm64`、`natives-windows-x86`、`natives-linux`、`natives-linux-arm32`、`natives-linux-arm64`、`natives-linux-ppc64le`、`natives-linux-riscv64`、`natives-macos`、`natives-macos-arm64` 和 `natives-freebsd`。本轮真实运行平台为 Windows x64；其他平台须选择匹配包并执行相同验收，不能从制品存在推断通过。

模块 POM 只传递 Java 绑定，不默认下载或加载所有平台二进制。调用方负责部署原生包、字体字节与许可；没有匹配二进制时抛 `NATIVE_UNAVAILABLE`，不回退到系统字体或网络资源。原生代码与自定义组件一样需要可信运行环境，不构成恶意字体沙箱。

## 加载、轴与 palette

~~~java
import com.mirakyux.export4j.opentype.OpenTypeFont;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

var base = OpenTypeFont.load(Files.readAllBytes(Path.of("fonts/variable.ttf")), 0);
var instance = base.withAxes(Map.of("wght", 700d, "wdth", 90d));
var selectedPalette = instance.withPalette(1);
~~~

`load` 复制最多 32 MiB 的 SFNT／TTC 字节，集合成员从 0 开始。`withAxes` 替换完整坐标集合；未指定轴使用字体默认值。未知轴、非有限数和超范围值抛 `INVALID_AXIS`，不静默夹取。`withPalette` 要求 palette 存在；默认索引为 0。对象不可变、可共享，不持有常驻原生句柄。

`id()` 是字体程序和集合成员的 SHA-256 身份；轴坐标与 palette 另由 `coordinates()`／`palette()` 表达，缓存实例时必须同时考虑它们。`axes()` 提供每轴最小／默认／最大值，`ascent()`／`descent()` 是所选实例的 em 度量。

字体 OS/2 禁止嵌入或仅位图嵌入标志会被拒绝；不允许子集化的字体保留其限制。标志检查不能替代真实授权核查。

## 单 run 塑形

`OpenTypeShaper.shape(font, text, options)` 处理一个方向／脚本 run，推断缺省方向和脚本，支持 ISO 15924 脚本、BCP-47 语言及显式 feature 字符串。它不做整段双向分段；混合方向使用下述组件。

输出 `Run`／`Glyph` 包含真实 glyph ID、原文 UTF-16 起止、X／Y 坐标和 advance，单位为 pt，X 向右、Y 向下。横排、RTL、TTB、BTT 使用同一原文簇契约；变体轴进入度量和轮廓，CFF2 同样参与插值。单 run 的绘制以一个逻辑文本锚点保留原文，逐字形来源仍在 `glyphs()`，不把 run 的矩形选区声明成逐字形精确选择。

## 换行、分页与竖排

~~~java
import com.mirakyux.export4j.opentype.OpenTypeText;
import java.util.List;

String text = "天地12玄黃，Hello 世界。";
var component = OpenTypeText.builder(text, List.of(instance))
        .fontSize(18)
        .lineAdvance(30)
        .flow(OpenTypeText.Flow.VERTICAL_RIGHT_TO_LEFT)
        .orientation(OpenTypeText.Orientation.MIXED)
        .tateChuYoko(2, 4)
        .build();
~~~

示例中的字体须实际覆盖给定文字；应用可依次提供 CJK、Latin 等显式字体。组件实现 `PageableComponent`，可放进 `ComponentBlock`；`layoutDetails(context, firstPageHeight)` 同时返回分页片段和原文放置范围。组件只使用自身字体栈，不借用 `ComponentContext` 字体猜测资源。CR／LF／CRLF、短初始区域、跨页来源均原样保留。

横排使用 JDK 双向算法分段、按完整字素选择显式字体，按脚本与方向合并 run。断行先查语言边界，整段过宽时按塑形簇寻找可容纳位置。水平正文使用真实行框的逻辑锚点，避免阅读器将可视 RTL run 再次重排；选区是行级范围。竖排支持从右到左或从左到右的列序：`UPRIGHT` 全部直立，`SIDEWAYS` 侧置，`MIXED` 按 CJK／假名／谚文等字符范围选择直立，其余侧置。该选择规则不是对任意未来 Unicode 版本完整 UAX #50 数据的承诺。

纵中横范围使用原文 UTF-16，必须互不重叠、覆盖完整字素且不含换行，只能用于竖排。组合先水平塑形，再统一缩入一 em 字格，保持内部比例和原文来源。一般竖排不再依赖逐字符固定 em 模拟；旧字格入口仍用于其明确的简单场景。

## 彩色字形

COLRv0 层、COLRv1 实色／线性／径向／扫描渐变及合成、sbix／CBDT 中的 PNG／BGRA 图像由 HarfBuzz 在布局阶段评价。palette 和当前前景色显式传入，透明结果保持透明，不用黑色轮廓覆盖空着色。彩色结果按 `colorDpi` 生成带 alpha 的图像，PDF／SVG／PNG 共享结果；因此彩色字形不承诺无限放大的矢量清晰度。

字体内 SVG 图像等不能由当前着色器处理的格式抛 `UNSUPPORTED_PAINT`，不冒充已支持；字体外资源不外联。测试覆盖自编 COLRv0／COLRv1 几何、28 种合成模式和真实 Noto COLRv1／sbix 字形。未穷举全部外部字体／设备／阅读器。

## 错误、预算与资源

缺字默认 `ERROR`。`missingGlyphPolicy(PLACEHOLDER_WITH_WARNING, '?')` 显式使用占位符并返回原文 UTF-16 警告，逻辑源仍为原字符。回退字体返回 `FONT_FALLBACK`；多码点 run 的诊断不伪造一个原字符码点。占位符本身也缺字则失败。非法字体、轴、输入、预算和取消不能通过缺字策略绕过。

默认最多 100,000 UTF-16 输入、4,096 次候选测量、4,000,000 UTF-16 累计测量工作；run 最多 1,000,000 glyph、2,000,000 轮廓段，布局最多 10,000 片段。彩色字形默认每 glyph 4,000,000 像素，run／候选布局累计 64,000,000 像素；中间画面保守计费上限 256 MiB，每 glyph 最多 100,000 paint 回调，渐变最多 4,096 stops，内嵌 PNG／BGRA 在解码前检查字节与像素。它们是声明资源和工作预算，不等于进程 RSS 上限。

同步操作在 Java 循环和原生绘制回调中检查 `ExecutionContext` 取消；一次不可中断的原生调用仍须返回才可结束。回调捕获异常，回到 Java 后抛出，不能让异常穿过 JNI。blob／face／font／buffer／回调／raster image 均按操作关闭，不持有全局字体缓存或无限重试。最终文件的原子替换继续由既有保存入口负责，直接写入调用方流仍遵守流所有权契约。

## 原生静态 CFF PDF

该能力属于现有 PDF 后端，不要求原生 OpenType 模块。普通静态 CFF 字形 run 使用 name-keyed Type1C 或 CID-keyed CIDFontType0C 完整嵌入，按 Unicode 编码页生成映射，多个字体字典共享原始 CFF 程序；补充平面文字进入 ToUnicode。绘制保留测量阶段位置，不在后端重新断行。显式复杂塑形／轮廓 run 继续走已有中立轮廓。

CFF 可编辑字段仍不支持。PDF/A 合规需要检查最终文件，不能从“字体已嵌入”推断；调用方选用的 1b／2b／3b／4f 入口与标准限制继续生效。

验收见[原生 OpenType 验收](verification/native-opentype.md)，决策见 [ADR-0101](adr/0101-explicit-native-opentype-and-cff-embedding.md)。
