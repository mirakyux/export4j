# 离线 XML 片段与 include API

`XmlTemplate.Builder.fragment(name, source)` 登记调用方提供的 XML 字符串。主模板用 `<include ref="panel" prefix="first"/>` 把片段加入既有块结构；编译结果仍为 `DocumentTemplate`，数据绑定、主题、布局和后端继续走原有路径。编译器没有文件路径、URL、类路径扫描或资源加载回调。

## 最短调用

~~~java
var panel = """
        <fragment version="1">
          <heading id="topic" level="2">${title}</heading>
          <p style="note">${summary}</p>
          <numbered id="step" list-id="checks">待复核事项</numbered>
        </fragment>
        """;
var template = XmlTemplate.builder().theme(theme)
        .fragment("panel", panel).build().compile("""
        <document version="1">
          <include ref="panel" prefix="first"/>
          <include ref="panel" prefix="second"/>
        </document>
        """);
var document = template.render(Map.of("title", "复核记录", "summary", "已完成"));
~~~

`fragment` 根仅接受 `version="1"`，内部为现有普通块、控制节点和表格语法，也可为空。调用方如果从文件读取字符串，应自行限制和关闭资源，再登记得到的内容；模板本身不会请求任何外部内容。

## 静态名称与本地 ID

登记名与 include 的 `prefix` 必须匹配 `[A-Za-z_][A-Za-z_0-9-]{0,63}`，区分大小写，不接受 `${...}`。prefix 必填；嵌套时用 `-` 连接父前缀与局部前缀，组合后的前缀在全部分支和循环原型中全局唯一。不同父片段可使用相同子前缀，组合后的 ID／list-id 仍限 128 字符。Builder 最多登记 128 个片段，重复或非法登记名在配置入口失败；`build()` 固定登记快照，后续修改 Builder 不改已构建编译器。

| 原片段值 | `prefix="first"` 后的模型值 |
| --- | --- |
| 块 `id="topic"` | `first-topic` |
| 编号 `list-id="checks"` | `first-checks` |
| 指向本片段显式 `topic` 的字段 target | `first-topic` |
| 指向主模板 `global` 的字段 target | `global` |
| 未声明 ID 的块 | `first-xml-N`，避让全部显式声明 |

原片段的 ID／list-id 先按既有静态 ID 语法验证，数字开头等非法值不能靠添加合法 prefix 修复；拼接后的显式 ID／list-id 最多 128 个字符。编译器先收集整棵展开树的显式声明，再生成自动 ID；不能用 include 的源顺序覆盖其他节点。字段 target 只有命中该片段的显式块 ID 时才加前缀；外部目标保持，缺失目标继续由实际文档布局失败报告。自动 ID 不是可跨模板依赖的稳定引用接口。

不同 prefix 的编号列表互相隔离，同一片段里的相同 list-id 继续编号。派生的最终 list-id 若与主模板或另一 include 作用域重名，编译明确拒绝，不能悄悄合并本应隔离的编号。片段位于 each 时仍继承循环的数据别名，既有模板复制添加 `@0` 等块 ID 后缀，并重定位当前副本的内部字段；list-id 不添加迭代后缀，所以同一循环内的编号按文档顺序继续。内部 if 也继承同一词法作用域，不会重新解析数据中的 XML。

## 递归组合与拒绝边界

include 可用于主模板及片段的根、then／else、each 等合法块列表位置。活动 include 链最多 16 层；循环引用在编译阶段拒绝，超过深度预算报告 BUDGET_EXCEEDED。不支持 include 参数、模板表达式 ref／prefix、片段根元数据、行内或格内 include。格内现有 p／table 使用既有块格规则。

例如主模板以 `prefix="outer"` 引入 panel，panel 以 `prefix="leaf"` 引入 child，child 的 `id="target"` 成为 `outer-leaf-target`。panel 中静态 `target="leaf-target"` 指向这个子目标；child 内静态 `target="target"` 指向自身目标。声明收集覆盖子片段及条件分支；没有声明的静态目标保留为主模板外部 ID。不存在 `../` 相对父级语法。

动态 `target="${record.target}"` 直接绑定最终完整 ID，例如 `outer-leaf-target@0`，不再自动添加 include 前缀或 each 后缀。动态 href 在当前词法数据范围绑定，最终地址再次经过协议和凭据校验，详见[XML 模板 API](xml-template-api.md)。

主模板的全部分支、运行时零次 each 原型也会检查 include、prefix 和被引用片段的语法；非法片段不能通过未选择分支隐藏。未引用片段不解析，但其登记源码仍计入输入预算。调用方登记字符串是显式资源白名单；没有 DTD、外部实体、脚本或远程加载的降级路径。

片段展开使用当前编译器的主题、命名样式和资源白名单。静态 span／p 名称在编译时解析；传入的数据只按已有表达式绑定，不成为 include、属性或样式语法。片段的内部静态目标与外部字段目标在同一次最终布局中解析。

## 预算与诊断

主模板与全部登记源码的 UTF-16 长度合计受 `maxInputCharacters` 限制，Reader 最多读取扣除登记源码后的余额再加 1 个单元，且不关闭借用 Reader。只解析被引用源码，同一个 ref 只解析一次；解析累计元素／文字、展开累计元素／文字分别受现有 `XmlTemplate.Limits` 限制，每次 include 出现也计元素。子目标预收集同样检查数量及取消，不能通过递归指数展开绕过预算。所有源树先受 65 层硬上限保护，主模板中保留到最终树的普通节点还会提前按配置 `maxDepth` 检查；片段插入后的最终深度按展开结构检查，不保留 include／fragment 包装。16 层 include 链限制独立于最终 XML 树深度。实际 each 复制另受既有 `DocumentTemplate.Limits` 绑定预算约束。

未知 ref 等 include 相关问题和 include 展开预算错误使用主 include 的一基位置；普通主模板语法错误仍定位自身节点。片段内部语法错误保留片段内一基行列，并通过 `TemplateException.fragmentName()` 提供可选登记名；错误 message 不回显登记名、正文或资源路径。名称由调用方登记，应用可选择如何显示，不能把原 XML 当作新的执行指令。

## 样例与独立验收

[XmlIncludeExample](../examples/src/main/java/com/mirakyux/export4j/examples/XmlIncludeExample.java)生成一页静态重复片段和两页词法引用样例。静态部分将同一片段加入两次，验证局部 ID、自动 ID、编号列表隔离、主题 note／span 与表格，输出 PDF、SVG 和标准 DOCX。引用部分在 each 中继承 record，在 if 中选择片段；内部字段指向当前副本，外部字段保留第一页编号 7，输出 PDF 和原生字段 DOCX。SVG 在写入前拒绝非空交互数据。

~~~powershell
.\gradlew.bat :examples:xmlIncludes
build/verification/python-phase13/Scripts/python.exe scripts/verify_xml_includes.py
~~~

独立脚本直接核对最终 PDF 的来源顺序、真实字号／字体／颜色、编号值、实际字段页码及链接目的地，再读取 SVG 逻辑原文／矢量和标准 OOXML 的 ID、列表、字段／书签目标。期望值独立声明，不从 Java 值清单导入。Java 小样严格读回 DOCX 并比较完整块模型；只规范化标题、编号项及行内格中单个默认无链接运行的等价省略表示，仍完整比较 ID、格式、富文本、字段、列表与表格层级，不宣称任意 Word 无损互转。

本次成功隔离 Maven 调用方也纳入验收，全部四页由 Poppler 与 PDFium 独立渲染并逐页目视复查。没有新运行依赖，不复制外部文档库实现；自研模板语法与样例采用 Apache-2.0。

[递归样例](../examples/src/main/java/com/mirakyux/export4j/examples/RecursiveIncludesExample.java)与[阶段五十八](verification/phase-58-recursive-includes.md)进一步覆盖父到子目标、两次词法复制、主模板外部目标、动态完整 ID 和图文链接。运行 `:examples:recursiveIncludes` 及 `python scripts/verify_recursive_includes.py`，检查最终 PDF 与标准 OOXML，并双渲染全部新页。
