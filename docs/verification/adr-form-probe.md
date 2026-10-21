# 密集 ADR 表单能力与 API 复杂度实测

## 链式 API 后续结果

本页原始探测记录保留首次实现的调用成本与缺陷。后续已用链式入口重写同一虚构数据样例，新增声明式行／格、默认单元格设置、最小行高和带页模板的统一导出。当前两种单元格声明顺序均为 98 pt，3／4 页、114 个单元格及 16 个勾选标记保持，独立几何和渲染检查通过。详见[链式 API](../fluent-api.md)和[阶段十四验收](phase-14-fluent.md)。

下述 283 行和 98／111 pt 是首次探测数据，不代表更新后的 API。后续已用公共 `FormText` 替换并删除示例勾选辅助，见[阶段十六](phase-16-static-forms.md)。原表单保持默认整组移动，超高组件单元格仍明确失败。

## 结论与范围

2026-10-15，使用现有公开 API 完成三页 A4 密集表单样例。包含 18 列用药表、两层合并表头、跨行单元格、灰色分区、静态勾选标记和最终页码。当前能力足以构建此类表单，编写复杂度仍偏高。

本项评估引擎能力和调用成本，参考表单仅用于结构观察。原 PDF、嵌入字体和真实病例数据均未加入仓库或示例包；样例使用虚构数据和已登记字体。没有像素复刻要求，也没有实现 PDF 导入或编辑。

实现见 [`AdrFormExample`](../../examples/src/main/java/com/mirakyux/export4j/examples/AdrFormExample.java)，独立检查见 [`verify_adr_form.py`](../../scripts/verify_adr_form.py)。本次只新增样例与检查，未修改库实现、公共契约或依赖。

## 实测结果

| 场景 | 结果 | 边界 |
| --- | --- | --- |
| 紧凑表单 | 3 页、114 个单元格、16 个选中标记 | 区段起始位置使用显式换页 |
| 描述文本增加为 12 倍 | 4 页，描述完整出现 12 次 | 行高增长，后续区段自动续页；单元格内部不拆页 |
| 描述文本增加为 100 倍 | `COMPONENT_LAYOUT_FAILED` | 组件单元格超过完整页高时明确失败 |
| 最终页码 | 两份样例分别为 1～3、1～4 | 通过 `PageTemplate` 页脚合成 |
| 声明顺序探测 | 同一表头为 98 pt 或 111 pt | 只改变 `GridTable.Cell` 声明顺序，行高变化 13 pt |

三个区段由 `Document.Builder` 和 `FlowColumn` 组合，使用两次 `pageBreak()` 分隔。区段内部按剩余高度流动。它不保证任意输入都保持三页，也没有为超高合并单元格添加自动附页业务逻辑。

独立脚本直接读取最终 PDF，检查单元格数量、页面归属、无面积重叠、页区边界、全部打印字符的边界、勾选路径数量、病例标记唯一性和描述完整性。所有检查通过。选中标记由方框字形和矢量勾组成；本例没有 AcroForm 字段，提取出的方框文字不包含选中状态。

## 编写复杂度

样例 Java 文件共 283 行，其中 265 行非空。约 100 行用于模板和文案，约 75 行为局部辅助代码，其余为导入、字体配置、输出和边界检查。行数受排版及长字符串影响，只代表本次实现成本。

| 操作 | 首次调用成本 | 当时的改进方向 |
| --- | --- | --- |
| 正文、区段和显式换页 | `Document.Builder` 可直接组合 | 保留现有短入口 |
| 多层合并表格 | 手填行列、跨度、列权重及每格样式 | 增加表格 Builder、默认单元格样式和覆盖诊断 |
| 最小行高、空白填写区 | 需要局部 `FormText` 预留高度 | 在表格层提供最小行高，让现有纵向对齐直接工作 |
| 静态行内勾选 | 需要局部矢量标记辅助 | 增加语义勾选组件，保留选中状态与布局信息 |
| 页脚和路径输出 | 先 `renderer.layout`，再后端写流 | 为统一输出入口提供页模板配置 |
| 超长合并单元格 | 当前明确失败 | 后续设计完整行续页或显式附页策略 |

首次探测时，`GridTable` 按输入单元格顺序逐次补足行高。先声明跨行格时，分配给首行的高度可能在后续单行格增高后形成多余空间。当时先建立两条独立表头行，再声明跨行格，可得到 98 pt；将相同单元格按跨行格优先排序后得到 111 pt。该缺陷已在后续链式 API 工作中修复，原始记录保留用于对照。

本例复用 `GridTable` 已有的行列合并和分页、字体组、绘制核心及 PDF 后端。最小高度和勾选辅助仅服务本例的普通水平文字，不是可直接承诺为通用富文本控件的公共实现。可编辑复选框属于既有导航／表单 API，本次未再次测试其填写保存能力。

## 复现与证据

~~~powershell
.\gradlew.bat -g build/gradle-validation :examples:build :examples:adrForm --no-daemon --console=plain
& build/verification/python-phase13/Scripts/python.exe scripts/verify_adr_form.py
~~~

独立 Python 环境按 `scripts/requirements-verification.txt` 安装。本次使用 pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0 和本机 Poppler。

- 构建：`:examples:build :examples:adrForm` 通过，Java 编译使用既有 `-Xlint:all -Werror`。没有新增 JUnit 用例，边界断言由可运行样例执行。
- 输出：`examples/build/adr-form/adr-form.pdf`、`adr-form-expanded.pdf`。
- 结构与渲染报告：`build/verification/adr-form/verification.json`。
- 构建与检查日志：`build/verification/adr-form-build.log`、`adr-form-check.log`。
- 全部 7 页由 Poppler 和 PDFium 独立渲染，并人工复查版面。使用 192 DPI 检查密集的 0.5 pt 网格线，Gaussian blur 2 px 对应 0.75 pt；两渲染器最大平均通道误差为 3.694513，最大变化像素比例为 0.029802，均通过既有密集生产样例的 4／0.06 容差。没有更新历史视觉基线。

未验证一般 PDF 无损复刻、原件字体一致性、DOCX 映射、任意长度的合并单元格续页或完整药物警戒业务校验。没有提交、推送或正式发布。
