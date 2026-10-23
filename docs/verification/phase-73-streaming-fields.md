# 阶段七十三：单向长表页码及准确总数

单向固定列路径支持实际呈现 PAGE，以及准确 `expectedPages` 声明下的 PAGES。表头按每次真实页重新解析；移页正文与拆行字段使用实际片段页。完整文档和单向路径共用固定宽字段绘制，不改变来源 UTF-16、预留宽度或分页。

8 项新增行为回归覆盖重复表头／移页、拆行原位置、源只计一次、实际字段及生成字符预算、未知总数跨两位数、未声明总数及未来引用拒绝、显式字体缺字诊断、原样回调异常、预留框溢出，以及总数过大／过小的旧目标保护、源关闭和写出器不完成。无字段的正数声明同样核对。全仓 1,357 项测试，零失败／错误／跳过；构建、SBOM、本地 bundle 与本次新隔离调用方通过。

可复现入口：

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:streamingFields --offline --no-daemon
python scripts/verify_streaming_fields.py
```

先按[制品指南](../releasing.md)生成本次隔离调用方。样例包含三页图文表头／七条移页正文、两页长格字段及两页未知总数；调用方两页同时验证旧预算构造器。独立检查使用 PDFium 提取实际文字、pypdf 读取 ActualText／链接／图片／边框，并对所有新增页执行 Poppler／PDFium 双渲染。共 9 页均通过双渲染及逐页目视；7 组历史独立回归（变宽表格、自动列合并／拆行、单向图文、行内图片、生产、进阶、能力）通过，没有刷新视觉基线。源次数与呈现计数只作对账，不代替最终文件检查。

未来引用、分节编号、块格、纵向合并及自动宽度仍独立；声明准确总数不代表隐式二遍读取，也不能把 PDFBox 累计页树解释为恒内存。契约见[API](../streaming-fields-api.md)及 [ADR-0080](../adr/0080-single-pass-page-fields.md)。
