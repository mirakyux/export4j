# 阶段九十二：PDF Form XObject 语义恢复

本阶段推进 GAP-10.6 的普通 Form 语义恢复单元，并为 GAP-14.2 增加独立输入与转换语料。Form 是复用页面绘制内容的对象，与 AcroForm 可编辑字段不同；本阶段不增加字段或原 Form 内容流编辑能力。设计见 [ADR-0095](../adr/0095-bounded-form-xobject-semantic-recovery.md)，使用规则见[语义恢复 API](../pdf-semantic-import-api.md)。

## 实际支持

恢复器按实际 `Do` 调用展开普通不透明 Form，支持嵌套、同一对象重复调用、正向等比缩放和平移。保留每个实例的文字、变换后字号／颜色、图片像素／显示尺寸，以及未旋转源页左上角物理 pt 来源框。退出 Form 后恢复调用环境，重复资源不会使第二个实例丢失。

有局部 Resources 时使用完整局部字典；缺省时继承调用环境。局部字典缺少明确引用的字体、图片或状态不能借用调用方同名资源补齐。未调用 Form 不再仅因其存在而跳过整页；已有全资源嵌入字体检查保持，未使用的坏字体资源不会因此放行。

隐式 BBox 与当前裁切区共同判断可见性。文字使用变换后的实际字形轮廓，图片使用显示矩形：

- 完全隐藏的文字丢弃，返回 `PDF_HIDDEN_TEXT / DISCARD / HIDDEN_BY_FORM_BBOX`。
- 完全隐藏的图片跳过，返回 `PDF_IMAGE_TRANSFORM / SKIP_MATCH / HIDDEN_BY_FORM_BBOX`。
- 部分裁切不能表达为当前模型，整页按 `PDF_STRUCTURE / SKIP_BLOCK` 拒绝或跳过；不保留已收集的同页正文、来源或暂存损失。
- `Tr=3` 不可见文字继续按既有隐藏文字规则丢弃；其轮廓跨越 BBox 不会使可见正文整页跳过。

显式 W／W*、透明组、旋转／非等比 Form、其他不支持图形状态继续遵守整页边界。恢复后采用语义流重新排版，来源近似文字框不能作为精确脱敏框。

## 输入检查与预算

检查实际调用链上的 FormType、BBox、Matrix、资源类型、引用与操作数，拒绝循环、非法几何及浮点溢出。即使前面已发现不支持的裁切，后面的缺失 `Do` 仍必须硬失败，不能借 WARN／IGNORE 跳页绕过。

唯一内容流的 token 在请求内按对象身份缓存并保持不可变，累计 token 数受 `maxObjects` 限制，避免重复实例反复解析大字符串。每次调用仍重新执行状态和资源检查，实际展开操作符、调用层数及 q 栈深度有界；文字、图片与模型按真实实例计费。字形轮廓的路径工作也受操作预算限制。部分裁切页丢弃候选时不退还已消耗的工作、字符或像素额度。隐藏图片先计像素并解码检查，再丢弃。

## 回归与独立语料

新增十项 Form 回归，覆盖变换后 18 pt 字号和直接仿射坐标、重复图像像素、嵌套字体角色及资源继承／遮蔽、未调用资源、完全隐藏文图、部分裁切整页策略、非法几何／引用、循环和重复执行预算。另保留普通图形状态的七项回归，显式裁切仍拒绝；不可见文字跨界单独验证 WARN／IGNORE 下可见正文保留与隐藏损失。

[create_pdf_form_fixtures.py](../../scripts/create_pdf_form_fixtures.py) 使用 pypdf 自主生成十一份、十七页输入，不使用 Export4j 或 PDFBox 造源。字体为已登记 Liberation Sans 的明确字节；源声明自定义字宽，嵌套局部字体故意采用不同字宽，防止以字体原生度量或调用方同名字体冒充资源作用域正确。

[PdfFormImportConsumer](../../verification/consumer/src/main/java/consumer/PdfFormImportConsumer.java) 仅使用本地 Maven 坐标：五种受支持源各以 WARN／IGNORE 导入、实际改字，并输出 PDF／DOCX／HTML／Markdown，共四十次转换；三种不支持结构各执行两种动作，共六次跳页；默认 ERROR、结构拒绝及畸形输入共十四次拒绝，并检查旧目标保护和源字节不变。

[verify_pdf_form_import.py](../../scripts/verify_pdf_form_import.py) 独立核对输入摘要、模型当前内容、全部来源坐标／字宽／字号／颜色、图片像素、标准 DOCX 样式、四格式内容唯一性及预检／实际损失。WARN／IGNORE 的处理与损失一致，IGNORE 只隐藏警告展示。

源隐藏文字样例具有明确反例：PDFium 文字提取能返回 BBox 外的 `HIDDEN SECRET`，但 Poppler 和 PDFium 的可视渲染都不显示它。验收要求恢复模型和所有转换结果均不含该文字，不能仅凭提取器输出当作可恢复正文。源页面逐行比较两种渲染器的墨迹、位置及字形覆盖，避免白底平均误差掩盖缺失文字。

PDF 图片的逻辑占位另行核对：PDFium 读取 U+FFFC，pypdf 读取不可见载体，验证器分别检查准确段序和标准 ActualText；不会通过删除任意字符来放宽正文断言。图片来源框使用独立仿射计算，再以两个源渲染器的实际边缘核实。

## 验证结果

- 全仓 **1,543 项测试、160 个测试集，零失败／错误／跳过**；最终 `build sbom publishLocalBundle` 与相关示例通过。
- 全新隔离 Maven 调用方的标准输出、字体兼容、上一批兼容与 `pdfFormImport` 全部通过；十五个自研库、十五条 BOM 约束和二十三项运行 SBOM 的制品与哈希核对通过。
- 十一份、十七页源输入完成四十次四格式转换、六次策略跳页、十四次预期拒绝；没有修改源字节或失败目标。
- 五个受支持源页、十个转换 PDF 页和六个不支持源页共二十一页双渲染，得到四十二张页面图；七张联系图已全部逐页检查。
- 上一批源字体／图形状态验收和字体、渲染、语义恢复、内容编辑、优化、生产、能力七组独立回归均通过，没有更新视觉基线。

## 复现与边界

Java 21、Gradle 9.2.1；Python 版本由 `scripts/requirements-verification.txt` 固定。Poppler 与 PDFium 以 144 DPI 独立渲染；新样例不依赖系统字体，不增加运行依赖或第三方样本。

从仓库根运行；其他平台使用 `./gradlew`。Python 应使用已安装固定验证依赖的隔离环境，`<consumer-build-directory>` 表示本轮新隔离目录。

```powershell
python scripts/create_pdf_form_fixtures.py
.\gradlew.bat build sbom publishLocalBundle :examples:production :examples:capabilities :examples:pdfRaster :examples:pdfSemanticImport :examples:pdfContentEditing :examples:pdfOptimization --no-daemon
.\gradlew.bat -p verification/consumer "-PconsumerBuildDirectory=<consumer-build-directory>" clean build run resolvedLibraries pdfCompatibility pdfFontCompatibility pdfFormImport --refresh-dependencies --no-daemon
python scripts/verify_pdf_form_import.py --consumer-build-directory verification/consumer/<consumer-build-directory>
```

隔离调用方成功后才更新本地成功目录指针，再运行字体、渲染、语义恢复、内容编辑、优化、生产与能力回归。生成物和原始日志位于忽略的 `build/`。

本单元完成普通 Form 的上述恢复子集，不代表共享 Form 原位编辑、任意裁切／透明合成、更多字体和图层／标签结构均已支持。GAP-10.6、GAP-14.2 继续按各自剩余范围保持部分支持；本轮不包含新的 Word 人工保存或跨平台阅读器验收。
