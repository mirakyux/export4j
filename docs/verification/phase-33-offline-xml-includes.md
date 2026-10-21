# 阶段三十三：平面离线 XML 片段复用

日期：2026-10-16。对应 GAP-07；[ADR-0040](../adr/0040-flat-offline-template-includes.md)和[调用指南](../xml-includes-api.md)。本单元已完成本地验收。

## 实现与兼容

`XmlTemplate.Builder.fragment(name, source)` 登记最多 128 个不可变字符串，主模板用静态 `<include ref="panel" prefix="first"/>` 在合法块列表复用。片段先展开到既有树，再预留全部显式 ID、生成自动 ID和编译绑定节点；不增加模型类型、依赖、资源加载或布局路径。

显式 ID／list-id 先验证原值，再加静态前缀。局部字段只改写命中片段显式块的 target，外部目标保持；each 副本仍用既有 `@i` 词法重定位，同组循环编号连续。全树 prefix 唯一，派生 list-id 与其他实例／主源碰撞明确拒绝，不能静默合并。所有分支、零次循环和深层块格均在编译时检查。

主源加全部登记源累计 UTF-16；解析与展开分别累计元素／解码文字，缓存不免除重复展开。最终深度按展开树检查，输入另有 65 层硬上限；普通主源节点保留原先首次超深位置。借用 Reader 只读扣除登记源后的余额加一，不关闭；取消后编译器可重用。片段叶错误通过 `fragmentName()` 保留来源，message 继续脱敏。

新增 **46 项测试**：36 项编译行为与 10 项布局集成。覆盖平铺等价模型／几何、目录偏移／分节重启、深层来源、字体及缺字、图文／字段、动态拆行表头、预算、零外联和取消。全仓 **853 项测试，失败、错误、跳过为 0**；完整构建 184 项任务、71 项执行，耗时 2 分 26 秒，编译及 Javadoc 通过。

## 验证

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle `
    :examples:xmlIncludes :examples:xmlTemplates :examples:xmlReferences :examples:xmlBlockCells `
    :examples:xmlThemes :examples:themes :examples:markdownTemplates :examples:blockCells `
    :examples:formStyles :examples:componentPdf :examples:advancedPdf :examples:production `
    :examples:capabilities --no-daemon --console=plain
scripts/verify_local_consumer.ps1
build/verification/python-phase13/Scripts/python.exe -X utf8 build/verification/run_phase33_regressions.py
build/verification/python-phase13/Scripts/python.exe -X utf8 build/verification/finish_phase33_include_check.py
~~~

本次成功隔离调用方为 `verification/consumer/build/validation-3b61e863e7d7`，重新通过正式坐标编译和运行。12 组独立检查全部通过：本项、原 XML／字段／块格／主题、Java 主题、Markdown、原子块格、表单样式、进阶、生产及能力。最终归档经 veraPDF 复验，144 条规则、10,629 项检查，失败 0。修正新独立脚本后只重跑本项，保留其余 11 组成功结果。

| 新输出 | 页数 | 独立证据 |
| --- | ---: | --- |
| 同片段静态复用两次 | 1 | 14 个块、两组编号各 1／2、两表、真实粗体／颜色／字号，标准 OOXML 及 SVG |
| each／if 交互复用 | 2 | 13 个最终字段、9 条内链，当前副本／外部目标、最终 XYZ 坐标和 5 个 OOXML 书签目标 |
| 隔离调用方 | 1 | 7 个块、两组独立列表、主题和 PDF／SVG／DOCX |

**4 页均经 Poppler／PDFium 双渲染，并逐张打开完整 Poppler 页面。** 两组编号独立，循环第二项继续为 2，外部编号为 7；条件内容、字段、表格与样式正确，无可见重叠或裁切。最大通道均值差 0.806036、变化像素比例 0.006817，低于 4／0.06；未更新视觉基线。

Java 样例严格读回完整 DOCX 块模型，只规范化标题／编号项／行内格的单个默认无链接运行等价表示；独立 OOXML 检查恰好一节、完整结构与实际原生编号。未运行 Word 人工保存、OpenSSL 或容量采样，不套用旧证据。

## 首轮问题与剩余范围

首次样例编译把 DOCX 导入的 SectionedDocument 当成 Document，已改为显式读取第一节；独立脚本另检查恰好一节。首次独立编号断言误要求连续条目共用 numId；既有导出为每项写 num／startOverride，实际值为 1／2／1／2。检查已改为解析原生 num→abstractNum→lvl 及 decimal／%1.／startOverride，保留列表身份、restart 和实例隔离断言。9 项内存变异均被拒绝，未删除编号验证换取通过；失败日志保留。

片段内部递归 include、路径／URL 加载、参数覆盖、动态名称／目标、多级 XML 编号、HTML/CSS 和设计器仍开放。未引用登记源不解析，但计总输入；静态 SVG 仍拒绝非空交互。GAP-07 保持部分支持，下一单元为目录标题／条目／页码的独立样式与颜色。OCR 排除。

证据：`build/verification/phase33-full-build.log`、`phase33-consumer.log`、`phase33-regressions.json`、`phase33-verapdf.xml`、`phase33-native-numbering-mutations.log` 和 `xml-includes/report.json`／`manual-review.md`。首轮失败另存为 `phase33-full-build-first-failed.log`、`phase33-regressions-first-failed.json`、`phase33-xml_includes-first-failed.log`。
