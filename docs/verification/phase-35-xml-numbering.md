# 阶段三十五：XML 多级十进制编号

日期：2026-10-16。对应 GAP-07；[ADR-0042](../adr/0042-xml-multilevel-numbering.md)和[调用指南](../xml-numbering-api.md)。本单元已完成本地验收。

## 实现与边界

XML `numbered` 的静态 `level` 支持 1～9，省略仍为 1。生产变化仅为原层级范围检查与 Javadoc；复用既有 `NumberedItem`、模板绑定、`SemanticBlocks` 及 DOCX 编号路径，没有新增模型、计数器、依赖或字体。

编译检查全部分支和零次循环原型的静态属性，并保留实际叶的一基行列／可选片段名。实际父计数根据选中分支和展开次序，在组件语义布局或 DOCX 导出阶段验证；`render` 不推测全局父完整性。当前级更新清空全部更深级，父计数保持，分页／分节不隐式重启。each 保留 list-id，include 按 prefix 隔离列表；同副本引用重定位后使用完整点分编号。

编号正文仍只接受文字与 span，link／字段／图像／块内容拒绝。普通段落可同时保存完整编号引用和被动外链。固定引用框放不下、高层级缩进无可用宽度、动态缺父与 int 溢出均明确失败；不缩字、猜测父项或改变旧布局算法。

新增 **26 项测试**：8 项编译／绑定及 18 项集成；全仓 **899 项测试，失败、错误、跳过为 0**。覆盖九级、真实字体／来源、跳级与祖先重置、独立列表、嵌套循环／片段引用、跨页连续来源、跨节、严格 DOCX 完整模型、预写出失败、缺字、宽度、取消，以及生成前缀 108／107 和最终引用 189／188 的独立预算边界。旧拒绝测试只移除现在合法的 2／9，其他内容白名单保持。

## 验证

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle `
    :examples:xmlNumbering :examples:xmlTemplates :examples:xmlReferences `
    :examples:xmlIncludes :examples:xmlBlockCells :examples:xmlThemes `
    :examples:markdownTemplates :examples:contentsStyles :examples:docxFields `
    :examples:production :examples:capabilities :examples:advancedPdf `
    :examples:componentPdf --no-daemon --console=plain
scripts/verify_local_consumer.ps1
build/verification/verapdf/verapdf.bat --format xml examples/build/capabilities/archive.pdf
build/verification/python-phase13/Scripts/python.exe -X utf8 build/verification/run_phase35_regressions.py
~~~

完整构建 184 项任务、75 项执行，耗时 1 分 23 秒，编译／Javadoc、SBOM 和本地 bundle 通过。隔离调用方为 `verification/consumer/build/validation-d9bca6950efd`，重新用正式坐标编译及运行。

12 组独立检查全部通过：本项、原 XML、XML 引用、平面片段、块格、主题、Markdown、目录样式、DOCX 字段、生产、能力及进阶。最终 PDF/A 经 veraPDF 复验，144 条规则、10,629 项检查，失败 0。未刷新任何历史视觉基线。

| 输出 | PDF 页数 | 独立证据 |
| --- | ---: | --- |
| 报告样例 | 2 | 12 项编号，3／3.2／3.2.1／3.3／4／4.1，循环子项 1.2／2.2 与独立实例 1.2；9 条最终引用和 3 条外链 |
| 隔离调用方 | 2 | 8 项编号，5／5.3，循环子项 1.1／2.1 与独立实例 1.1；7 条最终引用和 3 条外链 |
| 九级 DOCX | 0 | 原生 ilvl=8、完整九级 decimal 规则、有效祖先 startOverride，末级 2147483647 与原生完整 REF；不增加 PDF／Word 渲染声明 |

**4 页均经 Poppler／PDFium 双渲染，逐张打开完整 Poppler 页面。** 层级缩进、祖先重置、完整引用及循环实例正确，字段可见轮廓完整，无可见重叠或裁切。最大通道均值差 0.495046、变化像素比例 0.003716，低于 4／0.06。

独立 OOXML 检查解析 num→abstractNum→全部九级规则和各级有效覆盖，检查完整 `REF ... \w \h`、实际书签位置及宽度；私有提示只用于关联源身份。9 项内存变异全部拒绝，包括错 ilvl／lvlText、祖先／当前起点、引用开关／副本目标、移动书签保留 owner 和加入缓存值。原件未改；正式验证器与审查草稿内容一致。

## 审查修正与剩余范围

首轮测试误把中间 117 码点预算当作最终引用预算，已拆成生成前缀源与最终完整值两阶段，保留失败记录。其后精确修正 DOCX 单默认运行省略、窄栏实际失败级和显式 LF 的来源间隔，未修改生产行为或放宽断言。源码评估曾把 Java 编号模型的外链能力写为 XML 接受范围，现已明确区分；实际书签位置验证也补齐，避免只靠私有 owner 提示。

本项关闭 XML 多级十进制子目标，GAP-07 仍部分支持。任意编号格式、动态 level、编号内对象、递归 include、HTML/CSS 和设计器仍开放；页区／完整主题、递归续排、持续容量、更多文字／Word 兼容和 PDF 标准能力仍按缺口表推进。没有运行 Word 更新／保存、新 OpenSSL 或容量采样。OCR 排除。

证据：`build/verification/phase35-full-build.log`、`phase35-consumer.log`、`phase35-regressions.json`、`phase35-verapdf.xml`、`phase35-source-audit.json`、`phase35-numbering-review-mutations.json` 及 `xml-numbering/report.json`／`manual-review.md`。测试草稿首／二／三轮日志保存在 `phase35-tests-draft/`。
