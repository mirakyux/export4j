# XML 多级十进制编号 API

版本 1 XML 的 `numbered` 支持 1～9 级十进制编号。省略 `level` 时仍为 1。模板编译为既有 `NumberedItem`，通过 `DocumentEngine` 输出 PDF，或通过 `DocxAdapter` 输出原生 DOCX。层级扩展沿用现有编号、布局和引用路径，没有新增编号器。

## 最短调用

~~~java
var template = XmlTemplate.builder().build().compile("""
        <document version="1">
          <numbered id="parent" list-id="checks" restart="3">父项</numbered>
          <numbered id="child" list-id="checks" level="2" restart="2">子项</numbered>
          <numbered id="leaf" list-id="checks" level="3">末级项</numbered>
          <p>参见第 <number-reference target="leaf" width="48"/> 项。</p>
        </document>
        """);
var document = template.render(Map.of());
engine.export(document).backend(new PdfBackend()).to(outputPath);
~~~

上例假设已配置显式中文字体、`engine` 和 `outputPath`。三项绘制前缀依次是 `3. `、`3.2. `、`3.2.1. `；段落中的编号引用为 `3.2.1`，不带前缀末尾的点和空格。引用使用固定的 48 pt 宽度，完整值放不下时失败。

## 层级和重启

| 属性 | 契约 |
| --- | --- |
| `list-id` | 必填静态列表标识；相同列表共享计数，不同列表互相独立 |
| `level` | 可选静态整数 1～9；默认 1，不接受数据插值 |
| `restart` | 可选非负 int；省略或 0 表示当前级继续，正整数重设当前级 |
| `id` | 沿用 XML 静态块 ID 规则；编号引用使用目标块 ID |

每次更新当前级都会清空全部更深级。父计数保留，分页和分节不隐式重启列表。例如同一 `list-id` 按下表出现：

| `level` | `restart` | 完整编号 |
| --- | --- | --- |
| 1 | 3 | `3` |
| 2 | 2 | `3.2` |
| 3 | 0 | `3.2.1` |
| 2 | 0 | `3.3` |
| 1 | 0 | `4` |
| 2 | 0 | `4.1` |

编号要求所有祖先级均已出现。直接从 1 跳到 3、列表起始即为 2，或父级回退清零后跳过中间级，均会失败。对子级设置正 `restart` 也不能补造缺失父级。继续计数超过 int 上限时明确失败，不绕回负数。

组件输出按 `(level - 1) × fontSize × 1.5` 增加首行和续行缩进，原有段落缩进仍保留。编号前缀使用段落默认样式，正文的 `span` 保留原样式。高层级在窄区放不下时失败，不自动缩字或裁切。

## 条件、循环与片段

编译器检查全部 `if` 分支和 `each` 原型的静态属性。即使分支未选中或循环执行零次，非法 `level` 仍会失败。合法的二级原型可以通过编译；父项是否存在取决于最终展开次序。

`each` 给复制后的块 ID 添加 `@0` 等后缀，并重定位同副本的静态引用目标。列表 `list-id` 不添加迭代后缀，因此各次迭代继续使用同一列表。每轮先出现父项，再出现 `level="2" restart="2"` 子项时，两轮的完整子编号为 `1.2` 和 `2.2`。

`include` 用静态 `prefix` 限定片段里的 ID 和 `list-id`。同一 prefix 在循环内共享计数；另一个 prefix 独立开始，因此同片段的独立实例可得到 `1.2`。片段子级不能借用主模板或其他 prefix 的同名原始列表父计数。派生列表标识的跨 owner 碰撞仍在编译时拒绝，详见[离线片段指南](xml-includes-api.md)。

## 编译、绑定与导出失败

| 阶段 | 行为 |
| --- | --- |
| XML 编译 | 0、10、整数溢出、非整数和插值层级使用 `TemplateException.Code.INVALID_VALUE`；保留 `numbered` 叶的一基行列及可选 `fragmentName` |
| `DocumentTemplate.render` | 按实际条件和循环生成模型，保留列表标识、层级、重启值、完整格式和文字运行；不校验整份编号序列的父计数 |
| `DocumentEngine`／DOCX 导出 | 按实际序列校验父级；父项缺失、计数溢出和不支持的输出明确失败 |
| 布局和最终字段 | 检查可用宽度、行框、分页和完整编号引用的固定宽度 |

例如条件隐藏唯一父项，或空循环后只剩一个子项，编译和绑定都可以成功，实际语义输出会因父级缺失失败。这类动态失败不承诺原 XML 行列。直接使用核心 `DocumentLayouter` 不等于使用组件编号路径，应继续通过 `DocumentEngine` 输出编号文档。

XML 输入、解析／展开元素、文字和硬深度预算保持；绑定节点、块、行和码点另受既有绑定预算控制。编号层级是语义数值，不增加 XML 树深度。生成的完整编号前缀仍受布局码点、行、页和指令预算控制。本扩展没有新增提前计费、流式布局或恶意输入沙箱。

## DOCX 和内容边界

DOCX 写标准零基 `w:ilvl`、各级完整 decimal `w:lvlText` 和有效祖先／当前级 `startOverride`。当前实现可以为每项分配独立 numId；连续编号的验收依据是有效各级值及语义列表身份，不要求共享 native numId。

编号引用保留原生 `REF bookmark \w \h`，`\w` 请求完整段落编号。书签位于实际目标段落，引用不依赖缓存数字。本项目生成的文件支持既有严格语义子集往返；外部 Word 文档的任意起点、覆盖或定制标签仍按 STRICT 拒绝，或由 ALLOW_LOSS 返回诊断。PDF 与 Word 分页、通用 Word 编号恢复和阅读器字段更新不属于本能力。

XML `numbered` 正文接受普通文字、`span` 和文字 `link`；[阶段八十](verification/phase-80-xml-semantic-saving.md)保留标题／列表的被动外链。字段、图像和块内容保持拒绝，link 内也不能绕过此限制。标题仍为 1～6 级，bullet 的静态 level 为 1～9；Markdown、多格式编号、动态层级和格内块白名单不因此扩展。

## 样例与独立验收

[XmlNumberingExample](../examples/src/main/java/com/mirakyux/export4j/examples/XmlNumberingExample.java)生成两页 `report.pdf` 和对应 `report.docx`。第一页验证祖先回退及完整三级引用；第二页验证循环内片段的共享列表、同副本引用和独立 prefix。额外 `nine-level.docx` 由 XML 生成 1～9 级编号，末级重启为 int 最大值，并保留原生完整编号引用，不增加 PDF 页数。

~~~powershell
.\gradlew.bat :examples:xmlNumbering
build/verification/python-phase13/Scripts/python.exe scripts/verify_xml_numbering.py
~~~

独立脚本使用本次成功隔离 Maven 调用方目录，也可通过 `--consumer-build-directory` 显式指定。脚本声明期望原文与编号，不读取 Java 生成的值清单；检查 PDF 实际文字运行顺序、字号、各级缩进、完整引用、固定字段框、最终 XYZ 和外链矩形。PDFium 逐页校验完整来源行，字段轮廓锚点的提取次序不作为通用阅读顺序保证。

标准 OOXML 验证解析 numId 对应的有效多级规则和各级起点，检查引用开关、真实书签位置、预留宽度及 `od:listId`／`od:restart`。Java 小样按 STRICT 读回并比较完整模型，只归一标题／编号项中一个冗余、无链接、默认样式运行的精确省略表示，仍比较完整格式、ID、内容、字段、列表和链接。

样例和调用方的全部四页由 Poppler 与 PDFium 独立渲染，逐页目视复查与最终命令结果登记在[阶段三十五验收](verification/phase-35-xml-numbering.md)。设计和失败边界见 [ADR-0042](adr/0042-xml-multilevel-numbering.md)。本项不新增运行依赖、字体或外部资源。
