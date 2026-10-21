# 固定页区的字体角色

`DocumentEngine.fontRole` 注册的字体组现在会传入页眉、页脚、背景和前景组件的 `ComponentContext.fontRoles()`。自定义组件可以选择实际字体组，再复用标准文字组件测量和绘制。正文默认字体组与具名字体角色分别保存。

这次补齐的是固定页区的上下文传递。标准 `TextComponent` 尚无 `fontRole` setter；注册角色不会自动改变普通文字组件，也没有新增完整页区主题或 XML 页区语法。

## 注册字体并编写组件

字体仍由调用方显式提供。下面的辅助方法放入调用方类中，只读取指定文件，使用现有字体加载预算，不扫描系统字体或访问网络。仓库字体的来源和许可见[资源说明](../assets/fonts/README.md)；使用其他字体时须核对其嵌入和分发许可。

~~~java
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;

static FontGroup loadFont(Path path) throws IOException {
    try (var input = Files.newInputStream(path)) {
        return FontGroup.strict(List.of(TrueTypeFontLoader.load(
                input, TrueTypeFontLoader.DEFAULT_MAX_BYTES)));
    }
}
~~~

字体加载器借用输入流，不负责关闭。这个辅助方法自己打开流，因此在读取完成或失败后关闭；加载后的字体数据由字体对象保存，后续布局不需要重新打开文件。

自定义组件显式读取 `brand`，再把字体组传给现有 `TextComponent`。没有注册所需角色时明确失败，避免意外使用正文默认字体。

~~~java
import com.mirakyux.export4j.components.TextComponent;
import com.mirakyux.export4j.layout.ComponentLayout;
import com.mirakyux.export4j.layout.core.Component;
import com.mirakyux.export4j.layout.core.ComponentContext;

record BrandText(String text) implements Component {
    @Override
    public ComponentLayout layout(ComponentContext context) {
        var brand = context.fontRoles().get("brand");
        if (brand == null) {
            throw new IllegalArgumentException("missing font role: brand");
        }
        return TextComponent.builder(text).build()
                .layout(context.withFonts(brand));
    }
}
~~~

`withFonts` 只替换默认字体组，保留视口、字体角色、文字默认值和最终页上下文。上述组件不保留可变布局状态，不拥有字体，也不在布局阶段写文件；字体实现允许并发使用时，可以共享该组件。

把组件放入页区，并在引擎中注册角色：

~~~java
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.Documents;
import com.mirakyux.export4j.components.StyleScope;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.core.PageTemplate;
import com.mirakyux.export4j.model.PageSettings;

var regular = loadFont(Path.of("assets/fonts/LiberationSans-Regular.ttf"));
var brand = loadFont(Path.of("assets/fonts/LiberationSans-Bold.ttf"));
var header = StyleScope.builder(new BrandText("Report"))
        .fontSize(10).lineHeight(16)
        .color(new RgbColor(0.12, 0.24, 0.48))
        .build();
var template = PageTemplate.builder()
        .header(band -> band.height(24).gap(8).content(header))
        .build();
var document = Documents.builder()
        .pageSettings(PageSettings.A4)
        .paragraph("Body text")
        .build();
var engine = DocumentEngine.builder(regular)
        .fontRole("brand", brand)
        .pageTemplate(template)
        .build();
var result = engine.layout(document);
var diagnostics = result.contextualDiagnostics();
~~~

品牌页眉使用实际粗体文件；正文继续使用 `regular`。字号、行距、视口和坐标单位均为 point（`1 pt = 1/72 inch`）。固定行框必须容纳所选字体，内容不能自动缩小或裁切以适应页区。

页脚通过 `PageTemplate.footer` 配置。水印文字可放入 `background`，覆盖文字可放入 `foreground`，二者仍由 `Placement` 和 `Position` 决定位置；角色上下文对四类页区一致。需要旋转水印时，可信组件可以选择角色字体后委托已有 `WatermarkComponent`；本能力没有新增水印样式属性。

## 最终页面上下文

页区在正文分页、目录偏移和分节页码确定后合成。组件收到的值分别为：

| 属性 | 含义 |
| --- | --- |
| `width()`、`height()` | 当前 placement 的固定视口尺寸 |
| `pageIndex()` | 最终文档的零基物理页索引，含目录前置页 |
| `pageCount()` | 最终物理总页数，含目录前置页 |
| `displayPageNumber()` | 当前页显示页号，支持分节重启；不等于物理索引加一 |
| `fontGroup()` | 当前默认字体组，初始为调用方传入的正文默认组 |
| `fontRoles()` | 当前布局入口取得的不可变角色映射快照 |

显示页号可大于物理总页数，例如两页文件的某节从 7 开始编号。不能据此改写物理页索引或跳转目标。`SectionTemplate` 仍在节内首页选择 `first`，其他正文页按显示页号选择 `odd`／`even`；目录页沿用第一节的奇偶模板。

页眉和页脚仍先预留高度及间隔，再分页正文。把字体角色传入最终合成不改变正文预留、目录页数或最终字段解析时点。低层 `PageComposer` 不重排正文。

## 作用域与诊断

现有 `FontScope` 替换子组件默认字体组，并保留角色映射。现有 `StyleScope` 修改未声明文字属性的默认值，并保留字体、角色、视口和最终页号。两者可以嵌套；显式子组件属性继续优先。上例中 `BrandText` 使用 Builder 构造文字，因而继承周围字号、行距和颜色。

`fontRoles()` 中每个 `FontGroup` 保留自己的缺字策略。默认严格组遇到缺字失败；显式占位符组先尝试其完整字体栈，再绘制受支持的指定占位符并返回警告。页区选择的角色不会自动采用正文默认组的缺字策略。

`ComponentDocumentLayout.contextualDiagnostics()` 保留最终物理页和 `HEADER`、`FOOTER`、`BACKGROUND`、`FOREGROUND` 区域。文字警告保留原文 UTF-16 范围和实际字体标识。调用方决定如何呈现诊断，库不输出内部日志或正文内容。

## 低层合成入口与快照

需要直接合成已有显示页时，使用新增四参数入口：

~~~java
PageComposition composeDetailed(
        DisplayDocument body,
        List<PageComposer.PageSpec> specs,
        FontGroup fonts,
        Map<String, FontGroup> roles);
~~~

`specs` 必须与物理页一一对应，每项含原始 `PageSettings`、最终选定的 `PageTemplate` 和正显示页号。页尺寸必须与对应显示页一致，正文须已预留页带。返回值保留 `DisplayDocument`、placement 位置和组件诊断。

该入口在执行任何自定义组件前复制规格列表和角色映射。角色 map、key、value 均不可为 `null`；空映射合法。上下文内映射不可修改。角色字体组保持借用，合成器不关闭字体或访问资源。入口快照不保证复制期间可并发修改调用方的非线程安全 map；调用方仍须负责输入集合在调用边界的安全访问。

旧三参数 `composeDetailed(body, specs, FontGroup)` 委托空角色映射。原 `List<FontFace>` 重载和 `compose` 入口也继续使用空角色映射；没有角色配置时，旧绘制和诊断行为保持。需要角色的低层调用应显式使用四参数入口。

`ComponentDocumentRenderer` 的主布局入口为：

~~~java
ComponentDocumentLayout layout(
        SectionedDocument source,
        List<SectionTemplate> templates,
        Navigation navigation,
        FontGroup fonts,
        Map<String, FontGroup> roles,
        List<Note> notes);
~~~

该入口一开始就固定角色映射，正文、最终字段和页区共用同一快照。正文组件即使在同步回调中修改调用方原 map，也不会导致后续页区采用另一组角色。`DocumentEngine` 本身已在构建时固定角色；每次导出继续同步执行，不创建后台线程。

## 支持边界与样例

固定页区继续要求内容完整放入 placement。`PageableComponent` 在固定调用中只能返回一个片段；多片段明确失败，不能借最终页上下文自行追加页面。自定义组件仍是可信代码，必须返回有界、平衡的本地绘制，并自行声明线程安全。

`SemanticTable` 含待解析语义插槽，在固定页区读取 `commands()` 时仍明确拒绝。`TableComponent` 对字段、外链和具名字体角色的构造限制保持。传入角色映射不等于已经实现语义页区，不能绕过既有来源和字段解析约束。

本单元不提供标准组件 `fontRole` setter、字体角色自动级联、完整页区主题、XML 页区声明或 DOCX 页眉页脚映射。后续[阶段三十八](verification/phase-38-component-text.md)已提供 `FontRoleScope` 和原子 `RichTextComponent`，现在无需自定义委托即可选择角色及编写固定富文本，使用见[组件文字 API](component-text-api.md)。其余仍按[能力缺口表](capability-gaps.md)独立推进。

[PageRegionFontsExample](../examples/src/main/java/com/mirakyux/export4j/examples/PageRegionFontsExample.java)使用显式普通／粗体资源，生成两页固定页区样例，节内显示页号为 7 和 8。四类页区都经过已有 `FontScope`／`StyleScope`，实际文字仍由自定义组件委托 `TextComponent` 输出。

~~~powershell
.\gradlew.bat :examples:pageRegionFonts
~~~

行为回归见 [PageRegionFontRolesTest](../export4j-components/src/test/java/com/mirakyux/export4j/components/PageRegionFontRolesTest.java)，设计范围见 [ADR-0044](adr/0044-page-region-font-roles.md)。独立输出、隔离调用方、逐页渲染及未验证范围见[阶段三十七验收](verification/phase-37-page-region-fonts.md)。
