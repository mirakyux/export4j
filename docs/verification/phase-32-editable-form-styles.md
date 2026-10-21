# 阶段三十二：可编辑 PDF 表单显式样式

日期：2026-10-16。对应 GAP-04；设计见 [ADR-0039](../adr/0039-editable-form-styles.md)，调用见[表单样式指南](../form-styles-api.md)。本单元已完成本地验收。

## 实现与兼容

后端中立的不可变 `FormStyle` 为文字框、下拉框、复选框及单选组提供字号、文字／标记色、底色、边框及对齐。Builder 记录显式属性，黑色、透明、零宽均保留设置意图；`toBuilder()` 和四类字段的同步样式回调保持快照。布局只传递样式，不重排正文。

PDF 的 `DA`／`Q`／`MK`／`BS` 与全部 Off／Yes／选项外观一致。固定边框验证实际内框，自适应边框逐 widget 计算；文字框／下拉框的字号与对齐不能误用于选择标记。旧构造器委托默认样式，独立比较证明四类旧默认与显式空样式的实际字段字典和全部外观相同。record 组件数及值语义有变化，不宣称整体二进制兼容。

新增 **27 项行为测试**，覆盖显式位、不可变性、非法配置、最终目录偏移、完整字体、实际外观及零宽框；全仓 **807 项测试，失败、错误、跳过均为 0**。完整构建 177 项任务，63 项执行，耗时 2 分 22 秒；编译与 Javadoc 通过，无新增依赖或资源。

## 验证

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle `
    :examples:formStyles :examples:componentPdf :examples:advancedPdf `
    :examples:production :examples:capabilities :examples:xmlThemes --no-daemon --console=plain
scripts/verify_local_consumer.ps1
build/verification/python-phase13/Scripts/python.exe -X utf8 build/verification/run_phase32_regressions.py
~~~

本次最终隔离调用方为 `verification/consumer/build/validation-d5d3eb89a0bc`，重新通过正式坐标编译和运行。全部独立检查读取该目录；零框修复前的调用方和日志不冒充最终结果。

| 输出 | 页数 | 独立证据 |
| --- | ---: | --- |
| 样式表单 | 3 | 目录、ASCII、完整 Unicode 与默认对照；18 个字段、22 个 widget、34 个 AP 状态 |
| 隔离调用方 | 1 | 四类控件、完整 Liberation 字体；4 个字段、5 个 widget、8 个 AP 状态 |
| 自动填写保存后的两份文件 | 4 | 新文字／选项／勾选状态、样式和嵌入资源回读 |

**8 页全部经 Poppler／PDFium 双渲染，并逐张查看完整 Poppler 页。** 目录偏移、透明底、实际无框、彩色选择标记及默认对照正确，无可见重叠或裁切。最大通道均值差 1.158651、变化像素比例 0.007682，低于 4／0.06；未更新视觉基线。

**5 组独立检查全部通过**：表单样式、进阶、生产、能力和 XML 主题。最终归档经 veraPDF 复验，144 条规则、10,629 项检查，失败 0。自动填写使用 pypdf 及有界小样外观更新，不等于人工阅读器填写；未重跑 Word、OpenSSL 或容量采样。

## 审查修复与实际限制

独立检查及目视发现 `.border(0, color)` 虽写入 `BS/W=0`，PDFBox 仍因 `MK/BC` 画出默认 1 pt 框。已在解析后边宽为零时同时移除 BC，保留 BS 的零宽与模型设置意图；新增两类文字控件的实际 AP 无描边回归。默认和正宽控件保持。

首轮另修正测试使用的 COSNumber getter、PDFBox 真实颜色操作符及对齐内边距假设，未降低几何或样式断言。失败日志保留。

Unicode 样例当前值 `/V` 精确保留“完整字体：初始值”，外观提取可能得到“完整字体：初始値”。显式 LXGW 字体的 U+503C／U+5024 共用 GID 7194；独立检查逐字比较源 cmap、原始 AP CID 和 Identity 映射，报告保留真实提取别名与字体摘要，没有改字或模糊归一。不能据此宣称所有 Unicode 搜索／复制逐字一致。

固定字号不自动缩小，当前不测量字段文字是否完整放入目标框，过长或过高文字可能被外观裁剪。后端可表达的正字号／边宽／标记线宽至少为 0.0001 pt，过小自适应 widget 也可能明确失败；显式零框合法。按钮动作、JavaScript、富文本输入、PDF/UA、DOCX 内容控件及任意阅读器外观重建不属本项。

页区／目录主题和全局字体角色级联仍独立，GAP-04 保持部分支持。下一单元为显式 String 登记的平面离线 XML include；OCR 排除。

证据：`build/verification/phase32-full-build.log`、`phase32-consumer.log`、`phase32-regressions.json`、`phase32-verapdf.xml` 及 `form-styles/report.json`／`manual-review.md`。修复前记录保存在 `phase32-full-build-first-failed.log`、`phase32-full-build-before-zero-fix.log`、`phase32-regressions-first-failed.json`、`phase32-form_styles-first-failed.log` 和 `phase32-consumer-before-zero-fix.log`。
