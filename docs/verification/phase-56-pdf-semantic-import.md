# 阶段五十六：PDF 语义恢复与四格式转换

设计见 [ADR-0064](../adr/0064-bounded-pdf-semantic-recovery.md)，契约见 [API](../pdf-semantic-import-api.md)。

## 复现

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:pdfSemanticImport :examples:production :examples:capabilities
powershell -NoProfile -File scripts/verify_local_consumer.ps1
python scripts/verify_pdf_semantic_import.py
```

使用 `scripts/requirements-verification.txt` 固定的隔离 Python 环境及 Poppler。隔离调用方从本次 `build/repository` 制品解析；依赖已缓存时可用离线模式生成新的独立构建目录，成功后更新 `build/verification/consumer-build-directory.txt`。样例位于 `examples/build/pdf-semantic-import`，独立报告和双渲染位于 `build/verification/pdf-semantic-import`。

## 结果与边界

8 项导入行为回归覆盖标题／逐行段落／显式表格／实际图片、原始来源框、默认严格与 IGNORE 损失、精确字体角色映射、网格快照／切字／重叠、真实字符预算、取消、不返回假空文档、逻辑替代文字不覆盖字形、非法小数 Tr 拒绝和转换配置快照。

独立 pypdf 检查源文字；DOCX 验证标准 Heading1、p／p／tbl／p 顺序、真实表格单元格及嵌入媒体；HTML 验证标题／原生表格／图片；Markdown 验证标题及 GFM 表格。四目标均含 ALPHA→EDITED-CELL 的实际编辑，PDF 另由 PDFium 核对真实文字。8×6 原图的每个 RGB 像素及显示尺寸／来源位置准确，导入四项实际损失与预检逐项一致。新隔离 Maven 调用方实际生成 DOCX／HTML／Markdown 并通过内容检查。

源 PDF 与重新布局 PDF 共 2 页，分别由 Poppler 和 PDFium 5.13 渲染，全部查看。重排后流图片紧随表格，源绝对 Y 位置不保留；这是已报告的布局降级，不能将源／目标像素恒等作为恢复验收目标。支持的是内容和明确结构，不是一般文档原版重建。

完整构建、Javadoc、SBOM 和本地 bundle 通过。最终全仓 **1,259 项测试通过，失败／错误／跳过均为 0**，其中 PDF 适配器 112 项。固定验证工具为 pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0、python-docx 1.2.0。没有新增生产依赖或刷新视觉基线。全部字体资源必须嵌入静态 TrueType；含未嵌入标准字体的源、旋转／裁剪页和复杂结构不在本次恢复子集。多行原段落、自动网格／多栏识别、合并嵌套恢复及 OCR 不在本阶段范围。

## 整批交叉回归

编辑与转换批次结束后，从最新本地 bundle 新建隔离调用方，串行执行 17 组独立检查，全部通过：`verify_semantic_editing.py`、`verify_markdown_import.py`、`verify_html_import.py`、`verify_document_conversion.py`、`verify_docx_package_edit.py`、`verify_docx_style_import.py`、`verify_pdf_operations.py`、`verify_pdf_page_editing.py`、`verify_pdf_object_editing.py`、`verify_pdf_object_composition.py`、`verify_pdf_raster.py`、`verify_pdf_optimization.py`、`verify_pdf_content_editing.py`、`verify_pdf_semantic_import.py`、`verify_web_exports.py`、`verify_production.py` 和 `verify_capabilities.py`，均位于 `scripts/`。

本批保持实际源格式／内容、原包未改条目、图像像素、PDF 对象关系、预检／结果损失对账和原子输出检查。生产验证另核对 15 个库、BOM 与 23 个运行组件的实际制品／依赖／许可证／SHA-256。共享规约检查与改动文件的相对链接、隐私扫描及 LF 检查通过。Word 保存／导出仍沿用[阶段四十八](phase-48-docx-editing.md)的未验收边界；离线 Maven 验证不代表远程仓库发布或远程 CI 成功。
