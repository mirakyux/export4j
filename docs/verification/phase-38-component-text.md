# 阶段三十八：组件字体角色与固定富文本

日期：2026-10-16。对应 GAP-04；[ADR-0045](../adr/0045-component-text-authoring.md)及[使用指南](../component-text-api.md)。

新增 `FontRoleScope` 与原子 `RichTextComponent`，复用既有字体作用域和共享文字路径。注册角色选择完整字体组，运行保持显式字号／颜色／偏移／装饰；不改已有公共 record，不增加依赖或资源。

## 行为验证

新增 27 项测试：11 项角色作用域、16 项固定富文本。覆盖真实字体、两种作用域顺序、短区域、窄原子组件、旧分页片段、语义来源／序号、目录偏移和分节页码、未知角色、完整缺字策略及 UTF-16、逐项继承／恢复、同步回调快照、复杂文字轮廓、装饰／偏移、固定行框失败、输入／输出预算及取消恢复。

定向及完整构建通过，全仓 **975 项测试，失败／错误／跳过均为 0**。完整构建 187 项任务、76 项执行，耗时 1 分 3 秒；编译、Javadoc、SBOM 和本地 bundle 通过。隔离调用方为 `verification/consumer/build/validation-e54dcc5ce422`，独立编译和执行成功。未出现 Java 编译／Javadoc 警告；隔离 SNAPSHOT 仍有既存 changing-modules 提示。

~~~powershell
.\gradlew.bat -g build/gradle-validation :export4j-components:test `
    --tests '*FontRoleScopeTest' --tests '*RichTextComponentTest' `
    :examples:componentText --no-daemon --console=plain
~~~

完整构建追加 `build sbom publishLocalBundle :examples:componentText :examples:textPdf :examples:flowPdf :examples:tablePdf :examples:componentPdf :examples:advancedPdf :examples:production :examples:capabilities :examples:styleDefaults :examples:propertyStyles :examples:themes :examples:contentsStyles :examples:pageRegionFonts :examples:mixedColumns :examples:semanticTableFlow :examples:gridSemanticFlow`。随后执行 `scripts/verify_local_consumer.ps1`、`scripts/verify_component_text.py --consumer-build-directory verification/consumer/build/validation-e54dcc5ce422` 及 `build/verification/run_phase38_regressions.py`，均使用固定工具环境。

独立预检首先发现检查脚本漏算既有段后 6 pt 间距；实际 PDF 的组件字号与行距正确，按源格式修正预期，不修改生产或放宽阈值。失败日志保留。

## 输出与后续复评

样例两页覆盖页眉、页脚、背景、前景、普通正文与区域文字，调用方一页覆盖公共入口及实际角色字体。独立检查真实嵌入字体、文本绘制状态、字号／RGB、源字体推导的精确基线／相邻推进、装饰、SVG 实际轮廓／控制点／尺寸及可见状态。28 项受控输出变异全部拒绝，原件哈希未变。字形数量与轮廓检查限本组纯 Latin 固定样例，不推广为复杂塑形或通用 SVG 正确性证明。

全部 **3 页**由 Poppler／PDFium 双渲染，逐张打开完整页复查，未见裁切、重叠或丢字。最大通道均值差 **0.507426**、变化像素比例 **0.003558**，低于 4／0.06；没有刷新基线。另有 12 组既有检查通过：页区字体、目录、DOCX 字段、生产、能力、进阶、默认样式、逐属性样式、主题、混合栏、语义表格区域流及合并语义格；独立 veraPDF 通过。加本次输出检查共 13 组。

下一单元先显式表示列表编号／项目符号样式，再设计 XML 默认角色与颜色，避免编号仍使用原默认字体。完整主题、XML 页区、连续富文本分页和高级兼容目标继续独立。OCR 排除。

证据：`build/verification/phase38-{focused,full-build,consumer,component_text}.log`、`phase38-tests.json`、`phase38-regressions.json`、`phase38-verapdf.xml`、`phase38-review-mutations.json`、`component-text/report.json`／`manual-review.md`。
