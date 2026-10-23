# 阶段六十三：变宽栏最低行数

## 行为与结果

`FlowText` 与 `RichTextComponent.flow(policy)` 在变宽栏中支持断点前／后最低行数，保留完整原文、首行缩进和 UTF-16。短首区不消费来源；末行移动触发有界全源重测，只接受实际测量过且满足约束的分配。不可能约束和搜索预算耗尽明确失败。

全仓 1,312 项测试通过，零失败、零跳过；构建、SBOM、本地 bundle 和全新隔离 Maven 调用方通过。使用项目隔离 Gradle 缓存及已缓存依赖离线验证；不将全局脚本缓存错误算作功能检查通过。

## 复现

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:minimumLines :examples:variableColumns :examples:continuousColumns :examples:production :examples:advancedPdf --offline --no-daemon
python scripts/verify_minimum_lines.py
python scripts/verify_variable_columns.py
python scripts/verify_continuous_columns.py
```

在运行 Python 前，从本次 bundle 按[制品指南](../releasing.md)建立新隔离调用方。使用项目固定 Python 验证依赖和 Poppler。

## 独立证据

`examples/build/minimum-lines` 的普通显式行、自然换行和富文本共 10 页，调用方另有 2 页。独立解码最终 PDF 字形推进，检查 1∶3 真实栏宽、3／2 行断点、短首页完整跳过、全部源标识唯一有序及后续正文。首行 8 pt 缩进只出现一次；富文本 31 个运行的真实常规／粗体、两色和下划线完整保持；SVG 栏视口存在。

全部 12 页经 Poppler／PDFium 双渲染并逐页复查，可见笔画未匹配比例低于 0.1%，未刷新基线。原变宽栏的普通／富文本／复杂文字／图片混合流及连续等宽栏独立回归通过。测试另覆盖中阿组合文字、补充平面缺字诊断及不可行续区；编译无法检测这些换行、来源或几何回归。

不承诺预算内搜索全部数学可行方案，不扩展表格跨变宽区域。实现取舍见[ADR-0071](../adr/0071-minimum-lines-in-variable-columns.md)。
