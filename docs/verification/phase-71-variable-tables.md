# 阶段七十一：语义表格的变宽区域

`SemanticTable` 在实际不同栏宽下安排完整行／合并连通组，保留原始模型、行列、UTF-16、字段、图片、外链和字体作用域。核心按使用到的不同宽度准备有界候选，源预算一次，最终行预算只计实际呈现。等宽路径仍复用旧游标；不同宽度的半行／递归子树和组件网格明确拒绝。

新增 8 项行为回归，防止固定宽度行被错误复用、重复源计费、逐栏字段页码错误、丢失外链、嵌套宽度错误、候选警告重复以及全覆盖行生成虚假内容。测试还覆盖预算不足、过高原子组失败和之后的独立调用恢复。全仓 1,341 项测试，零失败／错误／跳过；构建、SBOM、本地 bundle 及全新隔离调用方通过。

独立验证普通表格 3 页、合并表格 2 页、嵌套表格 1 页、调用方 2 页，共 8 页。PDFium 真实提取与 ActualText 分别检查最终字段，pypdf 核对矩形、链接及图像。普通表格的窄栏每组呈现 3 行、宽栏 6 行；合并 owner 按实际宽度得到 120／48 pt 高度；嵌套子表按父格得到 106／222 pt。18 个唯一文字链接按实际换行生成 24 个矩形，图片及其外链各一次。全部 8 页经 Poppler／PDFium 双渲染并逐页目视，没有修改视觉基线。

11 组独立历史回归通过：语义区域流、网格语义流、递归表格、块格、横向合并、纵向合并、变宽文字、最低行数、混合栏、自动合并列／单向拆行和单向图文。检查使用本次新调用方输出，不引用旧构建目录。

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:variableTables --offline --no-daemon
python scripts/verify_variable_tables.py
```

先按[制品指南](../releasing.md)生成本次隔离调用方；验证使用固定 Python 依赖、Poppler 和 PDFium。候选至多 16 种宽度、累计 4,000,000 个源 UTF-16 单位，各候选另受行预算保护；这不是单向长表或常量内存承诺。契约及架构取舍见[API](../variable-tables-api.md)与 [ADR-0078](../adr/0078-variable-width-semantic-tables.md)。
