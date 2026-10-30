# 阶段一百一十一：设计器表格样式与合并

## 实现范围

表格预设即时生效；整表、表头、正文、列及选中格可调整文字格式。单元格网格支持 Ctrl 多选、Shift 矩形选择、合并和拆分。XML `style-if` 在词法数据作用域中有界执行，结果进入既有模型；正文编辑保留规则。属性说明图标紧跟名称，ID 保持首项。契约见[设计器指南](../designer.md#表格富文本与复杂属性)及[条件样式语法](../xml-template-api.md#单元格条件样式)。

条件样式限于行内格的文字外观，不含格背景、逐边边线及垂直对齐。合并不跨表头／正文，不接受不完整矩形；带规则的选区明确拒绝。动态正文不跨记录合并；拆分保留左上格内容，不推测原格内容的分配。

## 构建与公共 API

```powershell
.\gradlew.bat build sbom publishLocalBundle :export4j-designer:distZip --gradle-user-home build/gradle-validation --offline
.\gradlew.bat -p verification/consumer -PconsumerBuildDirectory=build/validation-designer-tables build run resolvedLibraries -g build/gradle-validation --offline
```

离线模式需要已缓存的锁定依赖。本轮完整构建共 1,821 项 Java 测试通过，无失败或跳过；API 基线对比没有破坏性变更及第三方类型泄漏，SBOM 和本地 Maven 制品生成通过。隔离调用方编译、运行、依赖清单通过；使用该调用方真实分发 JAR 的单独探针验证新 `CellStyleRule` 公共 API、XML 编译、`toBuilder` 规则保留与实际绑定。

`DesignerTableTest` 的 5 个案例覆盖完整矩形合并／拆分、富文本顺序、跨度恢复、非法选区、跨表头及带规则合并拒绝、精确大数比较、规则顺序、缺值／类型诊断、未选分支语法检查、块格拒绝、分节中的嵌套词法循环、相等但独立原型格、页区图片与字段保留和真实预览。全仓回归继续覆盖既有模板及布局契约。

## 浏览器与实际输出

在本地 HTML 应用中操作独立夹具，包含三列表格的两行正文，以及 `records` 驱动的姓名／分数表。数据为 HIGH／100 与 LOW／40；条件为分数大于等于 95，命中使用 `bold`、14 pt、22 pt 行高及 `#C0392B`。

| 操作 | 实际结果 |
| --- | --- |
| 选择紧凑网格 | 预设立即写入并预览，没有应用按钮 |
| 先改上内边距为 7，再连续修改表头字号／行高／颜色 | 全部修改保留，表头为 10 pt／16 pt／`#224488` |
| Ctrl 选择对角格后合并 | 提示选区须为完整矩形，源不变 |
| Shift 选择正文 2×2 矩形后合并 | 原四格文字按行列顺序出现一次，富文本保留 |
| 拆分后撤销 | 物理格恢复；撤销重新得到 2×2 合并格 |
| 修改规则、新增后删除规则、直接替换格正文 | 保存源码保留唯一规则及 `${record.score} pts` |
| 绑定数据改为 80 后恢复 100 | 自动排版成功，规则继续依绑定数据执行 |
| 检查最终属性面板 | ID 在首项；每个说明图标在名称后、控件前；非列范围隐藏列选择 |

操作后保存 `table-check.zip`，编码 PDF／DOCX／HTML，并执行独立验证：

```powershell
python scripts/verify_designer_tables.py --source build/verification/designer-tables --output build/verification/designer-tables/independent
```

该验证器读取上述界面操作生成的夹具；输入目录需包含保存的 ZIP、三份导出及对应 `report-*.json`。它不生成测试数据，也不使用同一输出后端读回。

- pypdf 6.10.0 检查全部正文唯一性、实际 Bold 字体和字号；PDFium 5.13.0 独立检查原文及字符填色，确认 `100 pts` 为红色、`40 pts` 为黑色，表头为蓝色。
- OOXML 独立解析确认 `gridSpan=2`、`vMerge restart/continue`、正文唯一性、实际字号／颜色和引用粗体样式定义。PDF／DOCX 的严格预检均无损失。
- HTML 保留横纵跨度、绑定文字及颜色；按 WARN／DEGRADE 报告纸张几何和字体角色损失，没有把语义流输出当成纸张布局。
- 唯一 PDF 页面经 Poppler 和 PDFium 全页渲染并复查：模糊后通道平均差 0.492424，差异像素占比 0.003953；合并线、正文顺序、颜色及未命中样式正常。

最终页面没有脚本错误。验收完成后恢复原模板、独立 JSON、全部字体／图片／许可，逐 ZIP 条目核对内容相同；仅保留一个本地设计器服务。原始日志、截图、备份与生成物只放忽略的 `build/verification/`。这些结果仅证明所述本地自动／浏览器验收，不代表正式发布或扩大跨平台／阅读器矩阵。
