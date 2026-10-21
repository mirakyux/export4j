# 阶段十验收：行内字段与注释锚点

## 本批范围

正文段落增加 `InlineField`，包含当前显示页码、最终物理页总数、目标页码、编号标签和注释标记。字段按固定宽度原子换行，在目录、正文和脚注页数确定后替换逻辑文字及轮廓，保持来源范围、行宽、基线和片段位置。

显式注释标记唯一绑定同段落的 `Note`，脚注跟随标记实际所在行。没有显式标记时保留旧段末上标；长脚注、尾注及每节单调预留继续有效。公共构造器保持兼容重载，不新增模块或依赖。设计见 [ADR-0014](../adr/0014-inline-semantic-fields.md)，调用见[字段 API](../inline-fields-api.md)。

## 构建与自动化验证

本地 Java 21、Gradle 9.2.1，生产和测试使用 `-Xlint:all -Werror`。

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:inlineFields :examples:production :examples:advancedPdf --no-daemon --console=plain
& scripts/verify_local_consumer.ps1
python scripts/verify_inline_fields.py
python scripts/verify_advanced_pdf.py
python scripts/verify_production.py --reader-copy examples/build/production/reader-filled.pdf
python scripts/verify_inline_images.py
python scripts/verify_mixed_columns.py
~~~

全量构建通过；累计 214 项测试，失败、错误和跳过均为 0。本批新增 12 项测试方法，覆盖：

- 目录偏移、分节重置、向前页码／编号引用和真实内部链接。
- 分页前后行数、原文范围、行宽、偏移和基线一致，补充平面字符及正文缺字警告保持原始 UTF-16 范围。
- 同段落首尾脚注分别归属各自标记页；长脚注续页、尾注唯一性及总页数包含脚注独占页。
- 续页中的当前页码及字段后的外部超链接保留原预留几何。
- 字段溢出、未知目标、无编号目标、重复／错误注释绑定、未知字体角色及严格缺字拒绝。
- 生成字段缺字策略、上下文诊断、交互／生成码点预算、模板展开后的标记重算。
- 表格字段拒绝、无效模型槽拒绝，以及 DOCX 在写入任何字节前拒绝含字段段落。

完整批次另运行文本、段落、表格、组件、行内图像和混合分栏示例。主要日志位于 `build/verification/inline-fields-*.log`；测试证据为各模块的 `build/test-results/test/`。

## 最终文件独立检查

`scripts/verify_inline_fields.py` 使用 pypdf 读取最终 PDF 内容、文字锚点、页面与链接，不调用 Java 布局算法。检查 SVG 逻辑文字与 PNG ZIP，并用 Poppler 和 PDFium 渲染全部 8 页。

| 样例 | 页数 | 关键证据 |
| --- | --- | --- |
| `fields` | 4 | 1 页目录、正文 2 页、重置节 1 页；当前页码为 2／3／7，总数为 4，目标标签为 `Figure 1`；两个行内引用都指向最终物理第 4 页 |
| `notes` | 3 | 15 行正文按每页 5 行分配；开头 `[1]` 及脚注位于物理第 1 页，末尾 `[2]` 及脚注位于第 3 页；一张图片只绘制一次 |
| `bidi` | 1 | 中文、阿拉伯文、组合字符与两个动态字段共存，完整逻辑文字及字段值保持顺序 |

行顶和引用矩形／目标坐标误差均在 0.003 pt 容差内。8 页均完成目视复查，无丢失、重复、非预期截断或重叠。固定字段宽度带来的空白符合声明契约。

Poppler／PDFium 的最大模糊后通道平均绝对差为 0.691434；PNG／PDF 最大值为 0.859286，均低于 4 的阈值。独立报告和联系页位于 `build/verification/inline-fields/report.json`、`contact-1.png` 和 `contact-2.png`。

## 隔离调用方与回归

调用方只从忽略目录 `build/repository` 解析本地 Maven 坐标，不引用 Gradle 工程。独立构建目录由 `scripts/verify_local_consumer.ps1` 生成，成功目录记录在 `build/verification/consumer-build-directory.txt`。新增 `consumer-inline-fields.pdf` 共 2 页，验证 `CONSUMER-PAGE=1 TOTAL=2 TARGET=2`、目标正文及链接，并通过两种独立渲染器检查。

原生产、进阶、行内图像和混合分栏独立验证通过。进阶历史视觉基线保持原样：5 页差异为 0，第 4／5 页延续此前的微小差异，平均绝对差分别为 0.007064／0.009889，变化像素比例为 0.000146／0.000204。原因尚未查明，本批没有刷新基线或声称全部页面零差异。

`reader-filled.pdf` 复用此前真实 Edge 填写保存证据，本批没有新增阅读器人工交互。独立结构和渲染检查不等于所有阅读器兼容。

## 支持边界

本批仅支持正文段落；不支持单元格／组件／连续栏内字段、自动宽度重排、注释自动跳转和 DOCX 字段映射。静态预览显式去除交互数据，PDF 保留目录与引用链接。复杂文字搜索／选择、PDF/UA、持续压力和跨系统验证仍未完成。

隔离 Maven 验证只发生在本地忽略目录。
