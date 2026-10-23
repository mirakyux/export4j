# 阶段六十四：单向长表横向合并

## 行为与结果

固定列 `StreamingTable` 的表头和正文支持 `TableCell.columnSpan`，各行真实锚点数量可以不同。合并宽度只扣一次内边距，IR／诊断保留物理起始列，重复表头只测量一次。锚点源计数与覆盖槽预算分离，不通过大跨度绕过结构限制。

全仓 1,316 项测试通过，零失败、零跳过；构建、SBOM、本地 bundle 和全新隔离 Maven 调用方通过。制品独立检查覆盖 15 个库、BOM 的 15 项约束及 23 项运行依赖。验证使用项目隔离 Gradle 缓存和已缓存依赖离线运行。

## 复现

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:streamingColumnSpans :examples:streamingTable :examples:streamingRichText :examples:columnSpans --offline --no-daemon
python scripts/verify_streaming_column_spans.py
python scripts/verify_streaming_table.py --consumer-build-directory <本次成功调用方目录>
python scripts/verify_streaming_rich_text.py
python scripts/verify_column_spans.py
```

Python 使用项目锁定验证依赖，Poppler 在 PATH 中。从本次 bundle 按[制品指南](../releasing.md)创建新的隔离调用方后再运行独立检查。

## 独立证据

`examples/build/streaming-column-spans` 包含 3 页 PDF 和逐页 SVG。两行表头分别全宽／合并两列，5 个正文行交替使用 2／3／1 个真实锚点。独立解析检查全部 20 个实际呈现格的边框、物理列位置、宽度、22 pt 行框、一次内边距及字形推进；源标识唯一有序。两处合并富文本的真实粗体、蓝色和下划线保持。

独立核对 14 个源锚点、源码点、20 个输出文字行、逐页读取次数 3／5／6 和关闭一次；表头重复不重复计算源。新增隔离调用方另有 1 页，覆盖标准公共入口和 2＋1 跨度。全部 4 页 Poppler／PDFium 双渲染并逐页复查，未刷新基线。

新增行为测试覆盖单行预读、表头布局复用、UTF-16／物理列错误、结构槽预算、跨度不足／溢出／整数上限、纵向跨度拒绝，以及输出第一页后失败仍关闭源一次、保留旧目标、清理临时文件。编译不能识别这些几何、预算或生命周期回归。原普通与纯富文本单向源、普通语义列合并的独立检查通过；不增加 Word 兼容结论。

横向合并不需要未来行，本项没有新增长期容量或硬内存上限结论。图片、链接、纵向合并、拆行和未来语义仍各自独立；PDFBox 累计对象边界保持。设计见[ADR-0072](../adr/0072-single-pass-horizontal-spans.md)。
