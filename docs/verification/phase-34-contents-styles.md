# 阶段三十四：目录独立样式

日期：2026-10-16。对应 GAP-04；[ADR-0041](../adr/0041-contents-appearance.md)和[调用指南](../contents-styles-api.md)。本单元已完成本地验收。

## 实现与兼容

新增不可变 `ContentsStyle`，分别声明目录标题／页码完整排版与标题／条目／页码颜色；条目排版继续使用 `TableOfContents.style`。Builder 支持值和同步回调、单项恢复、`toBuilder` 与不可变声明集合。显式默认值仍保留设置意图；回调失败不回写，逃逸的子 Builder 不影响已保存快照。

未声明标题和页码时按当前条目派生，严格保持旧五参构造的零缩进、对齐与 STANDARD 断行规则。旧三参构造保留；record 增加第四组件的反射、解构、生成方法及二进制边界明确记录，不宣称整体兼容。

每条目录预留条目实际高度与页码固定行高的较大值，再计算最终目录偏移。真实页码只能完整放入固定 48 pt 列的一行，放不下明确失败；不构造“0”或“1”探测字形，不缩字、裁切或触发页码重排。最终字段、书签、链接、分节显示页码、评论和表单继续使用既有解析路径。

新增 **20 项行为测试**，全仓 **873 项测试，失败、错误及跳过为 0**。包含旧入口完整布局／绘制／交互等价、三处样式与颜色、较大页码行高使目录由 2 页变 4 页、跨节页码 9／最终总数 6、48 pt 超宽及多行拒绝、真实字形与 UTF-16 警告、精确来源／行／页／指令预算和取消恢复。历史独立基线另作回归，未以新入口互比替代旧输出证据。

## 验证

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle `
    :examples:contentsStyles :examples:componentPdf :examples:advancedPdf `
    :examples:production :examples:capabilities :examples:inlineFields `
    :examples:docxFields :examples:xmlTemplates :examples:xmlReferences `
    :examples:xmlIncludes :examples:formStyles --no-daemon --console=plain
scripts/verify_local_consumer.ps1
build/verification/verapdf/verapdf.bat --format xml examples/build/capabilities/archive.pdf
build/verification/python-phase13/Scripts/python.exe -X utf8 build/verification/run_phase34_regressions.py
~~~

完整构建 182 项任务、77 项执行，耗时 2 分 52 秒；编译、Javadoc、SBOM、本地 bundle 通过。隔离调用方为 `verification/consumer/build/validation-34ad0fa8f13a`，重新以正式坐标编译及运行，生成新的目录样式文件。

报告样例为一页目录和一页正文，目录使用 22／11／14 pt 的蓝标题、绿条目、红页码；第二级长条目在目录分两行，在正文仍为一行。调用方另有目录／正文两页，保留默认黑色条目、18 pt 标题和 12 pt 页码。独立检查最终实际字体、RGB、嵌入字体、固定列、右边界、字符位置、两级书签与精确 `/XYZ` 目标。

**4 页均经 Poppler／PDFium 双渲染，逐张打开完整 Poppler 页面复查。** 三处外观、层级缩进、换行和正文顺序正确，无可见重叠或裁切；目录链接覆盖实际行框。最大通道均值差 0.669993、变化像素比例 0.005740，低于 4／0.06；未刷新视觉基线。

10 组独立检查全部通过：本项、进阶、生产、能力、行内字段、DOCX 字段、原 XML、XML 引用、离线片段和表单样式。最终 PDF/A 经 veraPDF 复验，144 条规则、10,629 项检查，失败 0；新消费目录只用于本次生成的结果，没有冒用被占用旧目录。

## 范围

本项仅关闭目录独立样式子目标。页区默认、完整品牌主题、全局颜色／字体角色级联、点引导线和 DOCX 原生目录仍开放；不宣称 PDF/UA 或任意阅读器兼容。没有新增依赖或资源；没有操作 Word、运行新的 OpenSSL 或容量采样。OCR 排除。

证据：`build/verification/phase34-full-build.log`、`phase34-consumer.log`、`phase34-regressions.json`、`phase34-verapdf.xml`、`phase34-source-audit.json` 及 `contents-styles/report.json`／`manual-review.md`。
