# XML 纸张、方向与页边距

XML 可以在根的首个元素声明静态页面几何。Java 继续提供默认配置、字体资源和输出后端；编译结果使用既有 `PageSettings`，不增加另一套布局器。设计见 [ADR-0043](adr/0043-xml-page-settings.md)。

## 最短示例

```xml
<document version="1" title="季度报告">
  <page size="A4" orientation="LANDSCAPE"
        margin="15mm" margin-top="20mm" margin-left="22mm"/>
  <heading level="1" size="18" line-height="28">季度报告</heading>
  <p size="12" line-height="20">纸张与页边距由模板声明。</p>
</document>
```

```java
var compiler = XmlTemplate.builder()
        .pageSettings(PageSettings.A4) // XML 未声明的项使用这里的默认值
        .build();
var document = compiler.compile(xmlSource).render(Map.of());
engine.export(document).backend(new PdfBackend()).to(outputPath);
```

`engine` 须事先注册显式字体资源。XML 的 `span role` 仍只选择已注册的真实字体组，不按字体名称扫描系统或加载文件。完整可运行例子见[模板](../examples/src/main/resources/templates/page-settings.xml)与[调用代码](../examples/src/main/java/com/mirakyux/export4j/examples/XmlPageSettingsExample.java)。

## 属性与覆盖顺序

| 属性 | 语义 |
| --- | --- |
| `size` | 静态 `A4` 或 `LETTER`；名称区分大小写 |
| `width`、`height` | 自定义宽高，必须成对，不能同时使用 `size` |
| `orientation` | 静态 `PORTRAIT` 或 `LANDSCAPE`；按基准尺寸的短边／长边重新排序 |
| `margin` | 统一覆盖四边页边距 |
| `margin-top`、`margin-right`、`margin-bottom`、`margin-left` | 各自覆盖对应边距，优先于 `margin` |

从 Java 编译器的 `pageSettings` 开始，先选择纸张或成对自定义宽高，再应用方向；边距先应用统一值，最后应用逐边值。未声明的项保持 Java 值。方向只改变宽高，不交换四边边距。不写方向时，显式宽高的顺序原样保留。

省略 `page` 和使用 `<page/>` 均保留 Java 的完整默认值。未配置 Java 默认值时沿用 A4、四边 54 pt。一个编译器可以分别编译不同纸张的模板；已编译模板、后续编译以及失败后的编译互不修改配置。

自定义尺寸例如：

```xml
<page width="148mm" height="210mm" margin="12mm" margin-bottom="14mm"/>
```

## 长度与校验

仅 `page` 的长度属性增加绝对单位：无后缀或 `pt` 为 point；`mm`、`cm`、`in` 按 `1 in = 72 pt = 25.4 mm` 换算。其他 XML 属性保持原来的 point 规则，例如 `<p size="12">` 和 `<column width="72"/>`。

长度使用 ASCII 非负十进制，支持 `12`、`12.5`、`.5`、`1.` 及小写单位后缀；不接受空白、正负号、科学计数、十六进制、NaN／Infinity、相对单位、计算式或数据插值。页面宽高须严格为正，边距可为零；换算结果须有限，四边必须留下正的正文宽高。页面模型没有额外的本批尺寸上限；实际布局和后端仍执行各自限制，不代表任意大小均能输出。

未知属性返回 `UNSUPPORTED_ATTRIBUTE`；尺寸冲突、未配对宽高、非法位置或非空内容返回 `INVALID_STRUCTURE`；非法值返回 `INVALID_VALUE`；`${...}` 返回 `INVALID_EXPRESSION`。错误继续提供脱敏的一基 XML 行列及可选片段来源，不包含原值、正文或资源路径。

## 位置、预算与兼容

`page` 仅在主 XML 根下合法，必须是第一个元素且最多一次；之前的结构空白和注释忽略。它必须是空节点，推荐自闭合；页内空白文字、CDATA、子节点均拒绝，注释不会形成内容。

条件两分支、零次循环原型、单元格以及实际引用的离线片段中均不能声明 `page`。`include` 即使展开为空也占主源的首个元素位置，不能让后置 `page` 合法。未引用片段沿用既有惰性解析规则，但其登记字符串仍计入总输入预算。

页面节点计入输入字符、解析／展开元素和深度预算；不生成语义块、不占块预算、不改变自动 ID。编译和渲染保持同步取消／超时与借用 Reader 契约，不访问文件、网络或字体。旧的无 `page` 模板保持原行为。

## 输出验证与剩余边界

```powershell
.\gradlew.bat :examples:xmlPageSettings
build/verification/python-phase13/Scripts/python.exe scripts/verify_xml_page_settings.py
```

独立检查覆盖 PDF MediaBox、实际文字／表格的非对称正文边界、表头重复和源行唯一性、SVG 逐页尺寸与堆叠、标准 OOXML `pgSz`／`pgMar`，并双渲染全部样例与隔离调用方页面。DOCX 原生尺寸为整数 twip（1/20 pt），读回按该精度量化，不承诺与原始浮点值完全相等或与 PDF 同分页。

根页面配置可由各节局部覆盖，见[XML 分节](xml-sections-api.md)；[固定页眉页脚](xml-page-regions-api.md)及[分域默认角色／颜色](xml-text-defaults-api.md)已有独立入口。完整主题定义、相对单位及 CSS `@page` 仍开放。A3／A5 等可用显式宽高，不增加未定义的纸张名称。字体文件继续由调用方显式注册。
