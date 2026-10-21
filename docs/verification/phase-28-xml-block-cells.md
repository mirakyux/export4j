# 阶段二十八：XML 多段格与原子内表

日期：2026-10-16。对应 GAP-07，设计见 [ADR-0035](../adr/0035-xml-block-cells.md)，编写方式见[XML 块格指南](../xml-block-cells-api.md)。复用阶段二十七的模型、绑定、布局及 DOCX；无新增生产依赖、字体或资源协议。

## 实现范围

版本 1 允许 `cell` 直接编写有序 `p`／静态 `table`／`p`。各段保留独立 ID、格式、字段与图文外链；所在格提供段落缺省文字格式。根表和内表最多两层，父连通组原子移页。普通行内表头继续重复，重复表头中的块格、块表拆行、第三层表、混合行内／块内容和格内动态语法在编译阶段拒绝。

外层 Each／Rows 可复制完整子树。同一复制范围的静态目标跟随确定 ID，外部目标保持。全部条件分支检查深度、几何和原子约束；源 AST 与绑定预算包含后代。源码格式错误指向实际段落位置，不再归于外层格。Markdown、HTML/CSS、include、动态目标及递归续排不属于此子集。

## 构建与调用方

84 个测试类、**711 项测试**，失败／错误／跳过均为 0；比阶段二十七新增 10 项编译／绑定和 7 项跨模块行为测试。覆盖未选分支拒绝、两层格式、父子双跨度、目录偏移、分节重启、四类字段、Each／Rows 副本互引、缺字层级路径、图文链接、原生 DOCX、窄字段失败与取消恢复。生产及测试使用 `-Xlint:all -Werror`，Javadoc 亦参与构建。

新隔离 Maven 调用方目录为 `verification/consumer/build/validation-4e33d9e5bc3c`，本地 bundle／SBOM 25 秒、调用方 55 秒通过。验证没有使用旧成功目录。

首轮全量命令已完成 `build sbom publishLocalBundle` 及 XML／块格／跨行样例，在重写旧 `column-spans/spans.docx` 时遇到文件占用。保留旧文件和进程，用忽略目录中的 Gradle init 脚本仅改变该样例工作目录至 `build/verification/phase28-column-spans-workspace`。独立验证相应读取该新目录。隔离脚本首版调用 Gradle 对象的 `project()` 失败，修为 `rootProject.project()`；原失败日志保留。最终完整构建与其余全部样例成功：3 分 14 秒，200 项任务、74 项执行。

## 独立输出

~~~powershell
& build/verification/python-phase13/Scripts/python.exe -X utf8 scripts/verify_xml_block_cells.py `
    --consumer-build-directory verification/consumer/build/validation-4e33d9e5bc3c
~~~

当前进程 PATH 显式加入固定 Poppler。沿用 pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0、python-docx 1.2.0 及 Poppler 26.07.0。

| 输出 | 页数 | 独立检查 |
| --- | ---: | --- |
| XML 报告 | 5 | 17 个绑定节点、父子双跨度、26 个边框、22 条最终链接、4 张图片；短首区整体移页、各副本引用及末尾正文 |
| 隔离调用方 | 2 | 6 个边框、8 条最终链接、2 张图片；叶段落几何、最终页码和副本内目标 |

**7 页**全部在 192 DPI 由 Poppler／PDFium 渲染，逐张查看全部完整 Poppler 页。最大通道均值差 1.652419、变化像素比例 0.017701，低于既有 4／0.06；未创建或刷新历史基线。原文 UTF-16、独立对齐和行距、来源路径、SVG 顺序、链接及图片矩形均通过。

原生 DOCX 与去除私有属性的标准包均由独立 XML／python-docx 检查。内外两张原生表保持 p/tbl/p、独立网格／双跨度、4 个字段、2 个书签及媒体关系；标准包严格导入成功。调用方四张表、8 个字段、4 个书签与去重媒体通过。样例初版把默认黑色单运行的冗余表示恒等误当作 DOCX 往返契约；现改用真实显式颜色，保留完整块树相等断言，不把默认运行规范化称为产品缺陷。

本阶段未重跑 Word 保存、OpenSSL 或容量；它们的旧证据不能标成本次结果。

**27 组历史独立验收全部通过**，包括上一阶段块格、跨行／列合并、单向长表、样式、模板、文字、图像、字段和各类区域布局。列合并检查使用本轮隔离目录，没有用旧 DOCX 冒充重新生成结果。本次最终 archive.pdf 另由 veraPDF 检查，144 条规则、10,629 项检查、失败 0。

## 下一项

复评后选择 GAP-09 的[单向富文本长表设计](../adr/0036-streaming-rich-text.md)：复用同一 TableCell 内容和显式角色，增加空运行预算并严格拒绝链接。主题、未来字段、完整 PDF 恒内存及持续容量仍各自开放。

证据：`build/verification/phase28-audit.json`、`phase28-consumer-retry.log`、`phase28-full-build.log`、`phase28-isolated-full-build-retry.log`、`phase28-regressions.json`、`phase28-verapdf.xml`、`xml-block-cells/report.json` 与 `xml-block-cells/manual-review.md`。复核脚本为 `build/verification/run_phase28_regressions.py`；独立列合并工作目录配置见 `phase28-isolated-column-spans.init.gradle`。
