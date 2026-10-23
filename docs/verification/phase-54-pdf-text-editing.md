# 阶段五十四：PDF 内容流文字替换

实现见 [API](../pdf-text-editing-api.md)及 [ADR-0062](../adr/0062-bounded-pdf-text-replacement.md)。本阶段验证真实 show 编码修改，不使用叠加绘制。

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:pdfContentEditing
powershell -NoProfile -File scripts/verify_local_consumer.ps1
build/verification/python-phase13/Scripts/python.exe scripts/verify_pdf_content_editing.py
```

6 项新增文字编辑测试覆盖 Tj／TJ、真实字形替换、同操作内多个匹配、未匹配字形原编码及坐标、后续操作 advance、显式字体及子集 ToUnicode、内部字距损失、容量／塑形跳过、marked content 整页跳过、Unicode／文字预算、配置快照、取消及原子目标。PDF 模块 104 项通过，无失败、错误或跳过；完整构建与本地制品成功，新隔离调用方实际生成替换输出。

独立 pypdf／PDFium 检查替换前的 SECRET 与替换后的 PUBLIC，匹配后的 KEEP 字形框保持在 0.015 pt 范围内。首次独立检查暴露新增字体未进入文档待子集队列的问题，现显式终结使用过的字体，并检查其实际嵌入程序和 ToUnicode。另一检查促使实现由整操作重编译改为只改命中范围，保留同操作其他字形的位置；未放宽坐标断言。

较短替换保留原后续位置，提取器会把其间隙推断为空格；调用方验证按精确非空白字形序列和坐标分别核对，不宣称提取字符串字节恒等。新增页均双引擎渲染并查看。安全删除证据独立归[阶段五十五](phase-55-pdf-redaction.md)。
