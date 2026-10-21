# 阶段十六验收：静态表单与语义组合复评

日期：2026-10-15。完成 GAP-05 的公共静态表单子集；修正 GAP-03 的定位表格外链损失边界。语义区域流和默认样式贯通仍未关闭。源码、矢量几何和样例自主实现，没有新增第三方依赖、字体或外部素材。

## 行为与兼容

- 新增 `FormText`、`ChoiceGroup`、`CheckMark`、`LabelValue` 和 `FillLine`；混合列提供对应链式短入口。公开签名没有第三方类型。
- 勾选框／单选圆点使用矢量轮廓，不请求标记字体字形。普通文字复用共享塑形、换行、字体策略、固定行框及原 UTF-16 诊断。
- `FormText`／`ChoiceGroup` 可以在短首区、合并格和连续栏中按完整行续排，测量一次，选择状态与短标签不会重复。`LabelValue` 的左右文字分别消费已测量行，不重复已经输出的标签，也不绘制白色表格背景。
- 单选组最多一项预选，允许全部未选；横向选项按普通文字断行，不保证选项整组保持。固定页区要求单片段。
- `TableComponent` 现在提前拒绝字段、文字／图片外链及具名字体角色；此前外链可能静默消失。对应内容继续使用顶层语义表格。普通静态富文本和单视口几何约束保持。
- 删除样例私有 `AdrCheckText`，ADR 样例只使用公共表单组件。主样例现为 227 行、217 非空行；上一阶段主样例与私有辅助合计 275／263。公共能力也增加实现代码，不能据此声称整个项目总代码减少。

契约见[静态表单 API](../static-forms-api.md)及 [ADR-0020](../adr/0020-static-form-components.md)。项目尚未正式发布；静态组件不等于 AcroForm、DOCX 语义或 PDF/UA。

## 构建与行为测试

按本次 Gradle XML 统计，46 个测试类、**277 项 JUnit**，0 失败、0 错误、0 跳过。新增 13 项行为测试覆盖：

- 字体不含选中框时仍有正确矢量和逻辑文字，不产生标记缺字警告。
- 短初始区跳过、选项唯一、重复布局确定性及固定区域失败。
- 普通标签严格缺字和占位符策略，代理对原始 UTF-16 范围及跨片段诊断。
- 无效图形坐标、无效标记索引、超宽图形、固定行框不足及标记数量预算。
- 阿拉伯文、组合字符与矢量标记共存，逻辑文字保留。
- 长合并格、短标签唯一、逐片段表头和选中标记完整行续排。
- 独立标记、填写线描边、标签值续页和构建快照。
- 取消作用域恢复与正常重试。
- 定位表格的文字外链、图片外链、字段／字体角色拒绝，普通富文本仍可构造。

全仓 `build`、Javadoc、源码包、SBOM 和本地 Maven 制品通过，Java 编译沿用 `-Xlint:all -Werror`。隔离调用方从发布坐标编译运行成功；没有访问内部 `FormMarks` 或准备流类型。隔离调用方没有 JUnit 源码，其运行检查不计入上述测试数。

## 独立验收

| 样例 | 结果 | 独立检查 |
| --- | --- | --- |
| 静态控件与复杂标签 | 共 7 页、94 个标记、42 个选中 | 90 个方框、4 个圆点；矢量轮廓与逻辑状态一一对应；1 条填写线 |
| 其中的长合并格 | 26 行，连续编号 ITEM001～ITEM026 | 正文顺序和唯一性；每页完整表头；短标签 ONCE 仅一次；完整行矩形位于单元格内 |
| 其中的当前页连续双栏 | 32 个选项 COL001～COL032 | 剩余高度、续栏与选择状态；尾部正文仅一次 |
| 隔离 Maven 调用方 | 1 页、5 个标记、3 个选中 | 新链式入口、固定标签列、填写线和独立标记；没有 AcroForm 字段 |
| 迁移后 ADR | 3／4 页，各 114 格、16 个选中 | 虚构内容与叙述数量保持；页码、边距、格边界、两种表头声明顺序均 98 pt |

`pypdf` 独立识别最终变换矩阵、填充方框环、选中多边形、圆环及填写线端点。文字标签与标记逻辑值通过 ActualText／普通文本交叉检查；SVG 独立解析核对来源顺序与选择状态。PDFium 检查 ADR 字形边界。

新静态样例 7 页、隔离调用方 1 页和迁移后 ADR 7 页，共 **15 页**全部使用 Poppler／PDFium 在 192 DPI 渲染并人工复查。沿用 blur 2 px、平均通道误差小于 4、变化比例小于 0.06 的既有密集表单容差；本批最大值分别为 **3.438929**、**0.028461**。没有刷新历史基线。

进阶布局、混合栏、合并格、行内图片、行内字段、生产输出及能力样例的独立脚本全部通过；既有表单可编辑回读和文字基线保持。验证工具为 pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0 和本机 Poppler。

## 命令与证据

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:textPdf :examples:flowPdf :examples:tablePdf :examples:componentPdf :examples:advancedPdf :examples:mixedColumns :examples:gridContinuation :examples:inlineFields :examples:inlineImages :examples:capabilities :examples:production --no-daemon --console=plain
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:staticForms :examples:adrForm --no-daemon --console=plain
& scripts/verify_local_consumer.ps1
& build/verification/python-phase13/Scripts/python.exe scripts/verify_static_forms.py
& build/verification/python-phase13/Scripts/python.exe scripts/verify_adr_form.py
& build/verification/python-phase13/Scripts/python.exe scripts/verify_advanced_pdf.py
& build/verification/python-phase13/Scripts/python.exe scripts/verify_mixed_columns.py
& build/verification/python-phase13/Scripts/python.exe scripts/verify_grid_continuation.py
& build/verification/python-phase13/Scripts/python.exe scripts/verify_inline_fields.py
& build/verification/python-phase13/Scripts/python.exe scripts/verify_inline_images.py
& build/verification/python-phase13/Scripts/python.exe scripts/verify_production.py
& build/verification/python-phase13/Scripts/python.exe scripts/verify_capabilities.py
~~~

新输出位于 `examples/build/static-forms/`。独立报告与联系表位于 `build/verification/static-forms/`，日志使用 `build/verification/static-forms-*.log`。隔离成功目录读取 `build/verification/consumer-build-directory.txt`；本批目录为 `verification/consumer/build/validation-d2e5fd98d3a1`。不使用旧目录冒充本次输出。

首次静态样例因包含阿拉伯文字体的默认正文行框不足失败，已显式为该样例配置 24 pt 正文行距；没有改变固定行框拒绝规则。初次编译的构造签名与执行方法名错误已修正；最终全仓构建和测试通过。

## 复评与下一入口

GAP-05 在上述范围内关闭。GAP-03 仍需要组件 IR 保留语义子片段及放置变换，再贯通最终字段、外链、来源诊断和全局预算，最后接入语义表格区域流。本批只关闭已发现的定位外链损失问题，未以静态绘制包装代替语义集成。

下一验收必须包括逐栏重复表头字段、最终分节页码、引用链接、图片外链、自动列宽、单元格拆行来源与行列／UTF-16 诊断。随后推进 GAP-04 默认样式贯通；当前仍须显式配置嵌套样式。

没有新增长期压力、RSS、一般 DOCX 兼容、完整 OpenType 或阅读器通用搜索证据；PDF/A／CMS 实现未改，未重复执行 veraPDF／OpenSSL 合规验收。
