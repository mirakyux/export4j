# 阶段三十一：XML 主题与静态命名样式

日期：2026-10-16。对应 GAP-04／07；设计见 [ADR-0038](../adr/0038-xml-theme-styles.md)，调用见 [XML 主题指南](../xml-themes-api.md)。本单元已完成本地验收。

## 实现与兼容

`XmlTemplate.Builder.theme(DocumentTheme)` 设置完整正文、六级标题、格、表格式和两类命名样式。`p`／`span` 可通过静态 `style` 选择完整值，再以 XML 显式属性覆盖。编译器固定快照，绑定与布局不再查名称，不增加依赖或绑定器。既有 XML 的 body／cell 独立作用域、显式 size 的 1.5 倍行高规则、块格分页策略拒绝及内表拆行限制均保持。

全部分支、零次循环和动态行原型均在编译时校验名称；非法或未知值脱敏，保留一基叶行列。新增 27 项模板行为／集成测试，覆盖完整值、顺序、快照、预算、取消、借用 Reader、实际分页、字体和标准 DOCX。另补齐共享布局的未知字体角色诊断：段落／格的角色错误保留运行起点 UTF-16，包括代理对前缀，完整嵌套来源仍在。

全仓 **780 项测试，失败、错误、跳过均为 0**。完整构建 186 项任务，81 项执行，耗时 1 分 41 秒；编译及 Javadoc 通过，未抑制警告。

## 验证

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle `
    :examples:xmlThemes :examples:xmlTemplates :examples:xmlReferences :examples:xmlBlockCells `
    :examples:themes :examples:capabilities :examples:production :examples:advancedPdf `
    :examples:textPdf :examples:flowPdf :examples:tablePdf :examples:inlineImages `
    :examples:mixedColumns :examples:continuousColumns :examples:multiFloats --no-daemon --console=plain
scripts/verify_local_consumer.ps1
build/verification/python-phase13/Scripts/python.exe -X utf8 build/verification/run_phase31_regressions.py
~~~

本次成功隔离调用方为 `verification/consumer/build/validation-7eaacb76a3b8`，重新通过正式坐标编译和运行，生成主题 PDF／SVG／DOCX。全部检查使用本次目录，未借用旧成功输出。

| 新输出 | 页数 | 独立证据 |
| --- | ---: | --- |
| 同 XML 的 blue／compact 两套主题 | 2 | 每套 28 项实际文字样式、9 个表边框，真实粗体、装饰／基线、六级原生标题、双网格、动态行和完整 DOCX 属性 |
| 交互主题 | 1 | 四类最终行内字段、两条内部与两条图文外链，实际矩形、标准 OOXML 字段／媒体关系，SVG 写入前拒绝 |
| 新隔离调用方 | 1 | 7 项文字样式、手算表边界／内边距、显式字体和标准 DOCX |

**4 页全部由 Poppler／PDFium 双渲染，逐张查看完整 Poppler 页面**。无可见重叠或裁切，表格／图片和下划线位置正确。最大通道均值差 1.43944、变化像素比例 0.013559，低于 4／0.06。未更新视觉基线。

**12 组独立检查全部通过**：本项、Java 主题、既有 XML／引用／块格、生产／能力／进阶、行内图片、混合／连续分栏和多浮动。最终归档由 veraPDF 复验，144 条规则、10,629 项检查，失败 0。未重跑 Word 保存、OpenSSL 签名或容量采样；对应旧证据不算本轮实测。

## 首轮问题与边界

首轮修正了两个测试假设：格内命名段落不能携带 `keepWithNext`；嵌套文字异常有中间包装，测试须沿原因链断言原始文字代码。实际角色诊断缺口已修生产代码并回归，未降低断言。样例 DOCX 的 Heading／编号项／普通行内格会省略单个无链接默认运行；只规范化这项等价表示，继续逐值比较 ID、完整格式、内容及层级。失败与精确差异日志保留。

XML 内主题定义、动态名称、CSS、页区／目录主题、全局字体角色级联及可编辑字段样式不由本单元提供。GAP-04／07 保持部分支持；下一单元为可编辑表单样式。OCR 不在本项范围内。

证据：`build/verification/phase31-full-build.log`、`phase31-consumer.log`、`phase31-regressions.json`、`phase31-verapdf.xml`、`phase31-xml-theme-docx-diff.log` 和 `xml-themes/report.json`／`manual-review.md`。
