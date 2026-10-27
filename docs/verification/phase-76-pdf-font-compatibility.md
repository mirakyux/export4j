# 阶段七十六：外部 PDF 嵌入 CFF 与连续显示指令

验收日期：2026-10-26。本批扩大 GAP-10.6 的嵌入字体子集及 GAP-10.7 的同状态连续 Tj／TJ 匹配，补充 GAP-14.2 的独立生成语料。字体、内容流和原版恢复的其他组合分别保持其缺口状态。

## 实现与行为回归

共享资源预检接受有效的 Type1／Type1C、Type0／CIDFontType0C 及匹配的静态 OpenType／CFF 容器。检查确切程序种类、嵌入字段及容器，拒绝损坏程序、冲突声明、可变字体与实际缺字。源流先执行解码预算；不请求系统字体或替代字形。新字体子集不进入模型，语义转换仍使用调用方配置的目标字体。

文字编辑将同一字体／状态下连续 Tj／TJ 解码为保留原始编码字形范围的序列；匹配不能切开一个编码字形的 Unicode 映射。未命中字形保留原编码、字距及位置，替换后的 advance 补偿维持后续位置。任何其他操作符结束序列；定位、换字体、颜色及 BT／ET 不会被拼接成一次匹配。容量不足等策略保留原范围，全部跳过不重写内容流。

顺序多次替换还修复了中间候选的字体问题：第一次新增的字体子集若未完成嵌入，第二次资源预检会拒绝该候选。现在每次编辑后完成本次子集嵌入，再读取下一次编辑的源，实际重复载入仍累计字体字节预算。

新增 **13 项行为回归**：8 项字体测试覆盖原始 CFF／OpenType、CID 字符集、Unicode、未命中文字位置、损坏／冲突程序、共享流声明、缺字、可变表、解码／文字预算及取消原子性；5 项连续指令测试覆盖 Tj／TJ 混合、内部字距损失、多处命中、连续多次编辑、失败时内容流原字节保持、定位／样式边界、ERROR／WARN／IGNORE、精确预算和旧目标保护。

最终 `build sbom publishLocalBundle` 及相关示例通过，全仓 **1,387 项测试，失败／错误／跳过均为 0**。新隔离 Maven 调用方完成 `clean build run resolvedLibraries pdfFontCompatibility`，实际使用本次本地制品；没有新增运行时依赖、字体资产或视觉基线。

## 实际文件与独立验证

输入由 `scripts/create_pdf_font_fixtures.py` 使用 pypdf 生成，不调用 Export4j 或 PDFBox。Type1C 和 OpenType 输入使用已登记许可的 Source Sans 3；CID 输入是原创的矩形／三角形字形，映射为 A／B，用于核对 CID 字符集与 Unicode，而不是中文字体兼容语料。

| 输入 | 验收结果 |
| --- | --- |
| `type1c.pdf` | 原始名称索引 CFF：渲染、字面替换、光栅脱敏及可编辑模型恢复 |
| `opentype-cff.pdf` | 静态 OTF／CFF：相同四个入口及原容器检查 |
| `cid-cff.pdf` | 原始 CID 索引 CFF：实际轮廓、CID／Unicode、替换后未命中坐标 |
| `split-shows.pdf` | 跨连续 Tj／TJ 的 `SECRET` 命中，非零字距单独报损，`KEEP` 坐标保持 |

四份输入各执行 DOCX／HTML／Markdown／重新布局 PDF 的 WARN 和 IGNORE，共 **32 次实际转换**；另有 **4 次默认严格导入的预期拒绝**。所有输出逐项核对预检／ACTUAL 报告、正文唯一性及损失；IGNORE 与 WARN 的功能及处理一致，IGNORE 仅不展示警告。

独立工具核对原程序容器、Unicode、替换后正文及未命中字形坐标。4 份脱敏结果均无可提取文字或源字体对象；指定像素为填充色，未选下部行的像素与源渲染完全一致。pypdf 的图像便捷入口遗漏间接 DecodeParms，验证器显式解析该字典并使用其独立 Flate／predictor 解码器，继续执行精确像素断言。

源、替换、脱敏和两种策略的重新布局 PDF 共 **20 个页面、40 份 Poppler／PDFium 独立渲染**，另有 4 张 Export4j 页面 PNG；全部逐页查看。PNG 与两个独立引擎逐像素差异检查通过。DOCX 检查标准 OOXML 文字，HTML／Markdown 独立解析后检查正文；不将这些检查称作 Word 或人工阅读器保存验收。

四组相关历史独立验证通过：PDF 渲染／图片提取、语义恢复与 8 个行内图片往返、内容替换／光栅脱敏、优化。它们均使用本次重新生成的示例和成功调用方输出。

## 可复现命令

从仓库根目录运行。Python 先安装 `scripts/requirements-verification.txt`，PATH 中需要 Poppler；本次使用 Java 21、PDFBox／FontBox 3.0.8、pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0、markdown-it-py 4.0.0、Poppler 26.07.0。

```powershell
python scripts/create_pdf_font_fixtures.py
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:pdfRaster :examples:pdfSemanticImport :examples:pdfContentEditing :examples:pdfOptimization --offline --no-daemon --console=plain
.\gradlew.bat -g build/gradle-validation -p verification/consumer "-PconsumerBuildDirectory=build/validation-<run-id>" clean build run resolvedLibraries pdfFontCompatibility --offline --no-daemon --console=plain
python scripts/verify_pdf_font_compatibility.py
python scripts/verify_pdf_raster.py
python scripts/verify_pdf_semantic_import.py
python scripts/verify_pdf_content_editing.py
python scripts/verify_pdf_optimization.py
```

隔离调用方成功后才将其本次绝对构建目录写入被忽略的 `build/verification/consumer-build-directory.txt`；Python 验证器据此读取 `output/pdf-fonts`。输入和报告位于 `build/verification/pdf-font-fixtures`、`build/verification/pdf-font-compatibility`，不提交生成物。`--offline` 适用于依赖和 Wrapper 已缓存的环境。

## 保留边界

未嵌入字体的显式映射、传统 Type1／Type3、复杂资源组合、图层和标签结构未由本批扩展。文字匹配不跨字体、定位、颜色或 Form 边界，不处理一般复杂塑形；新建 PDF 的可见 CFF 正文仍走既有轮廓输出。字体兼容不等于完整 PDF 恢复，更不等于无损跨格式转换、跨平台／阅读器兼容或硬内存沙箱。API 见[嵌入字体](../pdf-font-compatibility-api.md)和[文字替换](../pdf-text-editing-api.md)。
