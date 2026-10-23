# 阶段五十九：变栏宽文字与混合流

## 行为与范围

ContinuousColumns.columnWeights 支持按有限正权重分配栏宽，普通、自适应／显式 OpenType、连续富文本与混合流按实际宽度布局。短首区、原文来源、字体／样式作用域、内边距、完整行与整块图片沿同一同步路径传播。有界平衡保持页数和已跳过的首页。

当前变宽路径使用默认最低一行策略；最低多行策略、语义／网格表格和其他旧分页组件的跨变宽续排仍明确拒绝。等宽路径原支持范围保持。

## 可复现命令

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:variableColumns :examples:flowPdf :examples:mixedColumns :examples:componentText :examples:recursiveTables :examples:tablePdf
python scripts/verify_variable_columns.py
python scripts/verify_recursive_tables.py
python scripts/verify_mixed_columns.py
python scripts/verify_component_text.py
```

使用本次本地 bundle 重新构建隔离 Maven 调用方；成功目录按[发布验证](../releasing.md)记录。Python 使用锁定的独立工具版本。

## 输出

`examples/build/variable-columns` 的 plain／rich／complex／mixed 各为两页 PDF，并有对应 SVG。独立检查 60 个普通／样式词、36 组跨页阿拉伯文与组合字符、混合前后标签及唯一图片。实际 PDF 字形推进分别落入 85 pt 和 255 pt 栏，不以容器声明代替文字宽度证据；富文本实际字体、颜色与下划线保持。隔离调用方另外覆盖嵌套混合流和 40 个富文本词。

全部新 PDF 页使用 Poppler／PDFium 双渲染并逐页查看。密集文字的字体平滑导致灰度差异，报告保留原始平均误差与变化像素比例；另在 144 dpi 下检查双向笔画覆盖，1 pt 邻域之外未覆盖的墨迹比例须小于 0.1%。真实 PDF 字形推进与字符页边界另行验证，移除文字的负控制不能通过覆盖检查。有限样例不证明任意脚本、阅读器搜索／选区、表格变宽或 PDF/UA 合规。
