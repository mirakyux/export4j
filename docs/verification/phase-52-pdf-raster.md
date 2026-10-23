# 阶段五十二：已有 PDF 页面渲染和图片资源提取

按 [ADR-0060](../adr/0060-bounded-pdf-raster-and-images.md)及[API](../pdf-raster-api.md)增加独立的 PDF → PNG／JPEG 页面渲染和去重图片资源提取。未使用新文档 PNG 后端，没有 OCR。

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:pdfRaster
powershell -NoProfile -File scripts/verify_local_consumer.ps1
build/verification/python-phase13/Scripts/python.exe scripts/verify_pdf_raster.py
```

全仓 1,231 项 JUnit 通过，无失败、错误或跳过。11 项新增行为涵盖物理裁剪／旋转／UserUnit、密度和编码快照、透明 PNG、JPEG 背景与有损策略、显式嵌入字体／禁止默认字体、实际字形额度、Form 共享图片及软蒙版、stencil 跳过、输入／像素／输出预算、流所有权、取消和原子目标、源元数据／字段损失及加密权限。

新的隔离调用方以同次制品及缓存固定依赖通过。独立检查 7 张渲染图、1 张去重资源图、PNG／JPEG 密度、5 个 ZIP 的确切编码及各类损失。页面和表单 PNG 分别与 Poppler／PDFium 5.13 比较，全部新图逐页查看；最大单像素模糊均差小于 1.339，变化像素占比小于 0.01883。JPEG 明确背景并与相同背景的 PDFium 核对，资源图逐像素等于独立 Flate／predictor 解码结果。

首轮资源检查发现 pypdf 6.10 的图像快捷入口未正确处理本样例间接 DecodeParms，输出包含未去除的行预测字节；Poppler、PDFium 和源色块一致。验证器改为向其独立 Flate 解码器显式传入已解析的预测参数，严格检查解码长度和全部像素，未改源 PDF 或放宽断言。新增脚本使用锁定 Pillow 支持的像素访问 API。

渲染目前要求嵌入静态 TrueType；不支持的字体、外观及图案只能明确跳过整页。结果为 RGB 预览，非印刷色认证；像素／编码预算不等于硬 RSS 沙箱。资源提取包括已登记但未使用的图像，不声称恢复页面图片位置。
