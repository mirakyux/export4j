# XML 模板编辑与资源打包

`XmlTemplateBundle` 属于现有 `export4j-adapter-template`，用于保存、读取和修改 XML 模板源码及其显式资源。单份 XML 仍可直接交给 `XmlTemplate`；需要一起传递片段、图片和字体时使用普通 `.zip`，常见压缩工具即可打开。

编译继续使用 `XmlTemplate → DocumentTemplate`，变量、条件、循环、动态行、分节和页区沿用现有语法。这里编辑的是模板源码，不是从已渲染文档反推模板；绑定数据不会覆盖占位符。没有新增模型序列化器、模板解释器或自定义扩展名。

## 包内约定

| 条目 | 内容及名称 |
| --- | --- |
| `template.xml` | 必须存在的主源，严格 UTF-8，可保留注释、空白和 CRLF |
| `fragments/name.xml` | 对应 `include ref="name"`；名称为 ASCII 字母或下划线开头，后接字母、数字、下划线或短横线，总长最多 64 |
| `images/name.png`、`.jpg`、`.jpeg` | 对应 `image ref="name"`；名称不含短横线，最多 256 字符；扩展名须匹配实际 PNG／JPEG。同名不同扩展名拒绝 |
| `fonts/name.ttf`、`.otf`、`.ttc`、`.otc` | 原始字体字节；名称规则同片段。保存时不解析字体或推断角色，使用时由调用方显式加载、选择集合成员并配置引擎 |
| `licenses/name.txt` | 可选的原始版权／许可文本，严格 UTF-8；名称规则同片段，不参与模板编译 |

路径和扩展名区分大小写，不接受绝对路径、反斜杠、`..`、未知文件或子目录层级。可包含四个资源目录的空目录条目及 ZIP 注释／额外字段。支持单卷经典 ZIP 的 STORED／DEFLATE；加密、多卷、ZIP64、重复条目、CRC／目录不一致、截断和尾随内容拒绝。读取不解压到文件系统，也不执行任何资源路径或网络访问。

重新编码按名称排序，可重复编码；未修改文件的内容字节保留，ZIP 压缩表示、目录条目、时间戳、注释和额外字段不保留。资源包不是原 ZIP 字节保真编辑器。

## 创建、保存、打开和修改

```java
var original = XmlTemplateBundle.builder("""
        <document version="1" title="${title}">
          <page size="A4" margin="18mm"/>
          <heading level="1">${title}</heading>
          <include ref="intro" prefix="intro"/>
          <each items="items" as="item"><p>${item.name}</p></each>
        </document>
        """)
        .fragment("intro", "<fragment version='1'><p>待复核</p></fragment>")
        .build();
original.write(templatePath); // 普通 .zip，父目录须已存在

XmlTemplateBundle reopened;
try (var input = Files.newInputStream(templatePath)) {
    reopened = XmlTemplateBundle.read(input);
}
var edited = reopened.toBuilder()
        .fragment("intro", "<fragment version='1'><p>已复核</p></fragment>")
        .build();
edited.write(editedPath);

var template = edited.compile();
var document = template.render(Map.of("title", "巡检报告", "items", List.of(
        Map.of("name", "东门仓"))));
engine.export(document).backend(new PdfBackend()).to(pdfPath);
```

示例省略 import，路径和配置了显式中文字体的 `engine` 由调用方提供。需要页区或分节时，分别使用既有 `renderWithPageRegions`／`renderSections`。输出格式及拒绝／警告／忽略策略仍由各输出入口管理。

`toBuilder().source(xml)` 替换主源，`fragment`／`image`／`font`／`license` 替换指定资源，`remove("images/logo.png")` 删除精确条目。原对象保持不变，字体数组入参和返回值均复制。`source()`、`fragments()`、`images()`、`fonts()`、`licenses()` 可供编辑器读取；不提供图形化编辑界面。

资源可用 `.image("logo", image)`、`.font("Regular.ttf", fontBytes)`、`.license("Font-OFL.txt", originalLicense)` 显式加入。字体是否允许分发由调用方核对，许可文本应随资源保留。加载可使用 `TrueTypeFontLoader.load(bundle.fonts().get("Regular.ttf"))`；其独立字体支持和嵌入权限检查继续生效，TTC／OTC 使用既有集合成员入口。打包字节成功不表示字体一定可用于输出。

`compile(configure)` 接受可信的 `Consumer<XmlTemplate.Builder>` 配置运行时默认值。这些配置、绑定数据、字体角色、回调和引擎对象不写进 ZIP；需要可移植的格式应写在已有 XML 语法中，否则重新打开时显式提供相同配置。

## 草稿、诊断和预算

`build`／`read` 校验包结构、UTF-8、图片及预算，允许保存 XML 语法尚未完成或缺少引用资源的草稿。`compile` 才检查现有 XML 语法、全部分支和引用，沿用 `TemplateException` 的代码、行列和片段名称；绑定沿用 `DocumentTemplate.BindingException`。未引用片段的语法检查规则和编译器已有行为一致。

非法 ZIP、恶意 XML、未知语法、损坏资源、超限和取消直接失败，不通过 WARN／IGNORE 掩盖。这里没有隐式丢弃或降级源码的行为，因此无需另加损失策略；真正有损的格式导出继续使用[共享损失策略](semantic-editing-api.md#策略与报告)。

`Limits.DEFAULT`：编码 64 MiB、展开总量 128 MiB、512 条目（读取包含空目录）、XML 与许可文本共 1,000,000 UTF-16 单元、唯一图片共 40,000,000 像素。图片继续受 `ImageResource` 的单图 32 MiB／20,000,000 像素限制；单字体非空且最多 32 MiB。XML 编译／include 和数据绑定另受原有预算约束，放宽包预算不会放宽编译器限制。这是协作式逻辑预算，不是进程内存硬限制。

Builder 的 `.limits(...)`／`.execution(...)` 配置后续操作；`read(bytes, limits, execution)` 和对应流重载控制读取。超时／取消沿用同步 `ExecutionOptions`，阻塞的调用方流或配置回调在返回后的检查点观察取消。

`write(OutputStream)` 借用流，不关闭或刷新；先编码，实际写入失败仍可能留下部分字节。`write(Path)` 同目录临时写入后原子替换，失败或发布前取消保留旧文件并清理临时文件，不回退为非原子复制。只保证一个 ZIP 目标的原子性。

## 能力边界和验证

源码保存／重开／编辑／再次绑定已接入现有模板链路。已有模型可另用 [XmlTemplateWriter](xml-model-saving-api.md) 按明确子集转换成同一 XML／ZIP，并使用损失策略处理不支持内容；`bundle.conversion()` 可把无需绑定数据的静态模板接入统一转换。资源包本身不推断任意外部文档，也不提供导出文件内嵌恢复信息。一般模型恢复及原文档保留编辑仍见 [GAP-24](capability-gaps.md#自生成文档的恢复与往返编辑)。

完整调用见 [XmlTemplateBundleExample](../examples/src/main/java/com/mirakyux/export4j/examples/XmlTemplateBundleExample.java)，普通 ZIP 独立输入和 Maven 调用方见[阶段七十七验收](verification/phase-77-xml-template-bundle.md)，设计见 [ADR-0083](adr/0083-editable-xml-template-bundles.md)。
