# 阶段三十六：XML 静态页面配置

日期：2026-10-16。对应 GAP-07；[ADR-0043](../adr/0043-xml-page-settings.md)与[使用指南](../xml-page-settings-api.md)。本单元已完成本地验收。

主 XML 首个可选空 `page` 支持 A4／LETTER、成对宽高、方向、统一及四边边距，接受严格十进制裸数／pt／mm／cm／in。XML 显式值覆盖 Java 默认，未声明值保持；复用 PageSettings 和既有布局，无新增依赖、字体或模型类型。原主源检查确保空 include 不改变首位，实际引用片段及全部分支不能声明页面；未引用片段保持惰性。页面计入解析／展开／深度预算，不生成块或消耗 ID。

新增 **38 项测试**（32 项编译／绑定、6 项布局／DOCX），全仓 **937 项，失败／错误／跳过为 0**。覆盖词法、单位、方向、局部覆盖、全部分支、片段来源、两层预算、Reader、快照、成功／失败／并发编译隔离与取消恢复；实际分页保留完整行及 UTF-16 来源，标准 OOXML 明确整数 twip 量化。

## 验证与输出

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle `
    :examples:xmlPageSettings :examples:xmlTemplates :examples:xmlReferences `
    :examples:xmlIncludes :examples:xmlBlockCells :examples:xmlThemes :examples:xmlNumbering `
    :examples:markdownTemplates :examples:contentsStyles :examples:docxFields `
    :examples:production :examples:capabilities :examples:advancedPdf :examples:componentPdf `
    --no-daemon --console=plain
scripts/verify_local_consumer.ps1
build/verification/verapdf/verapdf.bat --format xml examples/build/capabilities/archive.pdf
build/verification/python-phase13/Scripts/python.exe -X utf8 build/verification/run_phase36_regressions.py
```

完整构建 185 项任务、71 项执行，耗时 2 分 25 秒，编译／Javadoc、SBOM、本地 bundle 通过。隔离调用方为 `verification/consumer/build/validation-9f48d877a91f`。13 组独立检查全部通过：本项、原 XML、引用、include、块格、主题、多级编号、Markdown、目录样式、DOCX 字段、生产、能力和进阶；PDF/A 独立 veraPDF 验证通过。占位符示例继续输出预期的两项缺字警告，不是编译警告。

| 输出 | PDF 页数 | 独立证据 |
| --- | ---: | --- |
| A4 非对称毫米边距 | 2 | 正文行 23／13，完整表头、真实粗体及末段 |
| Letter 横向 | 1 | 物理宽高 792×612 pt，8 行及非对称边距 |
| 自定义 148×210 mm | 2 | 正文行 15／7，完整表头与末段 |
| 隔离调用方 5×4 in | 1 | 边距 36／18／18／27 pt，4 行及末段 |

独立检查涵盖 PDF 零原点／物理宽高、逐格 28 pt 行高／72 pt 首列／剩余列宽、实际文字边界、逐页源行唯一与重复表头、真实字体程序；SVG 逐页原点／viewBox／页号及堆叠；标准 OOXML 整数 pgSz／pgMar 精确量化。全部 **6 页**经 Poppler／PDFium 双渲染，逐张打开完整 Poppler 页复查，无可见裁切或重叠。最大通道均值差 2.424144、变化像素比例 0.025515，低于 4／0.06；未刷新基线。

6 项正例／重编码控制和 12 项纯内存变异分别检查错纸张／原点、移动或缩短单格、假粗体权重、OOXML 差 1 twip／小数词法、SVG 原点／viewBox／页号。变异全部拒绝，原件摘要未变；不扩大为恶意输入安全证明。

## 修正与剩余范围

首轮两个错误假设已修正并保留失败日志：页内空白遵守既有严格空节点规则；毫米／A4 导出的 DOCX 回读按标准整数 twip 量化，不能要求原浮点值完全相等。生产未为样例放宽验证。独立检查进一步采用精确整数 twip 与逐格几何，避免相邻整数同时落入宽松误差带。

仅关闭模板内单一全局页面配置；XML 分节、页区、默认角色／颜色、完整主题、递归 include、HTML/CSS 和设计器仍开放。Word 更新／保存回读和跨平台验证未运行。OCR 排除。

复评发现固定页区丢失引擎注册角色映射，见[固定页区角色设计](../adr/0044-page-region-font-roles.md)；下一单元独立修复上下文，不把现有 StyleScope 配置当成新能力，不放行延迟语义插槽。

证据：`build/verification/phase36-{focused,focused-2,full-build,consumer}.log`、`phase36-tests.json`、`phase36-regressions.json`、`phase36-verapdf.xml`、`phase36-page-review-mutations.json`、`xml-page-settings/report.json`／`manual-review.md`。

阶段三十七收尾时，对两单元 32 个明确文件完成联合源码／文档审查：UTF-8／LF、终止换行、尾随空白、源码 SPDX／显式导入、Python 语法和 547 个本地链接全部通过，证据为 `build/verification/phase36-37-source-audit.json`。阶段三十六原测试／构建记录保留，不用后续累计测试数覆盖。
