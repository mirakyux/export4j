# 阶段五十三：PDF 压缩、去重与图片重采样

实现及边界见 [API](../pdf-optimization-api.md)和 [ADR-0061](../adr/0061-measured-pdf-optimization.md)。

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:pdfOptimization :examples:production :examples:capabilities
powershell -NoProfile -File scripts/verify_local_consumer.ps1
build/verification/python-phase13/Scripts/python.exe scripts/verify_pdf_optimization.py
```

全仓 1,240 项 JUnit 通过，无失败、错误或跳过。9 项新增行为覆盖无损解码、相同／不同属性资源、确切统计、默认严格损失、比例和配置快照、共享软蒙版、JPEG／错误处理、被动字段及附件保持、恰好解码额度的不可压缩流、取消／输出预算／旧目标和借用流；另验证多流页面不被解析器写入 Form 类型。

新隔离调用方使用同次本地制品及固定缓存依赖，通过构建与实际输出。独立解析核对 9 页、源绘制指令、图片维度／编码、字段／附件／元数据及确切大小；所有新增页用 Poppler 和 PDFium 5.13 渲染并查看。无损路径同引擎逐像素一致，显式缩小／JPEG 路径按既定图像质量范围通过。

对象样例由 441,344 字节变为 230,358 字节，8 个流重压缩并去重 1 个程序流。另一份原本紧凑的样例无损重写从 15,336 变为 15,352 字节；JPEG 输出也更大，验证统计如实返回，不宣称必然减小。

首次独立字段比较包含了重写后不同的间接对象编号，已改为比较标准字段类型、当前值、默认值、标志和选项；源数据和严格像素断言未改变。当前不保证任意 PDF 优化，蒙版等复杂图片重采样明确保留并报损。
