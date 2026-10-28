# 阶段九十七：PDF 编辑、标准与自动恢复整体验收

本阶段固定处理 GAP-10.7～10.12、GAP-11.1～11.3、GAP-20.1～20.2，共 11 个既有子项，不新增或重新拆分 GAP。设计见 [ADR-0097](../adr/0097-explicit-pdf-editing-recovery-and-standards.md)，总状态以[缺口表](../capability-gaps.md)为准。

## 验收范围

| 子项 | 实际交付 | 独立证据 |
| --- | --- | --- |
| GAP-10.7 | 同一行跨受支持字体、字号、颜色、字距及定位指令的文字替换；保留未命中字形位置，样式与间距降级显式报损 | 独立字体程序／内容流、命中数、逐字形位置及双渲染 |
| GAP-10.8 | 按实际图片绘制次序替换指定实例，沿 Form 调用路径隔离克隆；保留原变换、BBox、clip 和图层成员 | 共享实例／未选页引用及像素、alpha、关闭层不显现 |
| GAP-10.9 | 在明确不透明静态子集中重新构建保留文字、图像和剩余路径；清除源流、替代文本、附件和历史 | 去除新遮挡后仍无敏感几何／像素，真实字体子集及 ToUnicode、保留字形位置、旋转页面和裁切背景 |
| GAP-10.10～10.12 | 多行／comb／富文本／多选／自动字号、六种被动标记、FDF／XFDF、独立静态定位及流式重复 XFA | [阶段九十四](phase-94-pdf-extended-forms.md) |
| GAP-11.1～11.3 | 离线 PAdES B／T／LT／LTA、信任／撤销／时间戳／DSS 续期／DocMDP；显式 PDF/UA-1；PDF/A-1b／2b／3b／4f 生成和有损转换 | [阶段九十六](phase-96-pdf-standards.md)，标准检查与辅助阅读器分别记录 |
| GAP-20.1～20.2 | 显式 AUTO 恢复多栏、段落、标题／列表、重复页区、有线无线表格、合并／嵌套及跨页续表；显式网格优先 | [阶段九十五](phase-95-pdf-automatic-recovery.md)、独立来源／正文及标准格式检查 |

图片实例与文字夹具由 pypdf 独立生成，共 8 组、16 源页。16 份输出、32 输出页与源页共 96 张双引擎页面图；检查 WARN／IGNORE 同结果、未选页准确内容流及像素、范围外字形位置误差小于 0.02 pt。默认关闭图层的图片替换另有独立探针，Poppler 和 PDFium 均保持全白，无新增可见像素。

选择性脱敏必须保留真实范围外内容，不能以遮挡成功代替删除。裁切图片同时清洗隐藏像素并限制实际绘制区域；路径先做布尔减法再写出。文字对象、标记、Form、图形状态和字符映射均校验结构及累计预算，畸形输入不能依靠 PDFBox 容错变为成功。字体普通标识和任意隐写载荷不属于通用敏感信息发现保证。

选择性脱敏使用 18 份独立源文件，含 9 个正向与 9 个拒绝输入；调用方产生 18 份输出，验证 22 次拒绝和旧目标保护。80 张双引擎页面图组成 19 张联络图，全部逐张检查。164 个保留字形同时核对原点、字号、方向及双引擎实际墨迹；简单 TrueType 与新 Type0 在提取器中的字体度量表示差异不作为实际漂移，也不通过放宽墨迹几何断言获得通过。

全仓构建完成 174 个测试集、1,634 项测试，零失败、错误或跳过。回归包含同样式连续显示指令之后还有换行时不误报 `TEXT_STYLE`，以及 PDF/UA 图像不额外朗读空选择锚点。公共标准调用方另在没有 PDFBox／Bouncy Castle 的编译类路径中检查，防止内部同名重载使调用方被迫依赖第三方类型。

## 构建与隔离调用方

所有命令从仓库根运行。验证环境使用 `scripts/requirements-verification.txt`；生成物、私钥、原始日志、页面图及 JSON 报告保留在忽略目录。

```powershell
python scripts/create_pdf_instance_edit_fixtures.py
python scripts/create_pdf_extended_forms_fixtures.py
python scripts/create_pdf_automatic_fixtures.py
python scripts/create_pdf_selective_redaction_fixtures.py
python scripts/create_pdf_standards_fixtures.py
.\gradlew.bat build sbom publishLocalBundle :examples:production :examples:capabilities
.\gradlew.bat -p verification/consumer "-PconsumerBuildDirectory=<consumer-build-directory>" clean build run resolvedLibraries pdfInstanceEditing pdfSelectiveRedaction pdfExtendedForms pdfAutomaticRecovery pdfStandards pdfCompatibility pdfFontCompatibility pdfFormImport pdfExtendedFonts pdfFormEdit pdfPassive pdfComplexRecovery --refresh-dependencies --write-locks
python scripts/verify_pdf_instance_editing.py --consumer-build-directory verification/consumer/<consumer-build-directory>
python scripts/verify_pdf_selective_redaction.py --consumer-build-directory verification/consumer/<consumer-build-directory>
python scripts/verify_pdf_extended_forms.py --consumer-build-directory verification/consumer/<consumer-build-directory>
python scripts/verify_pdf_automatic_recovery.py --consumer-build-directory verification/consumer/<consumer-build-directory>
python scripts/verify_pdf_standards.py --consumer-build-directory verification/consumer/<consumer-build-directory>
python scripts/verify_production.py --consumer-build-directory verification/consumer/<consumer-build-directory>
```

Linux/macOS 使用 `./gradlew` 及对应 Python／veraPDF 路径；标准调用方可通过 `-Pconsumer.verificationPython=<python-executable>` 显式指定 RFC 3161 回调进程使用的 Python。隔离调用方只解析已发布到项目本地仓库的 Maven 坐标，全部任务成功后才更新本地调用方指针。

本次全新隔离调用方的全部 20 个任务通过。15 个库、BOM 的 15 条约束、26 个运行时 SBOM 成分与调用方解析坐标逐一一致；JAR、源码、Javadoc、POM、SHA-256 及必要许可通过独立检查。所有新增任务只使用本次 Maven 制品，未把源码类目录预验收作为最终结果。

五组新增独立检查全部通过；[阶段九十四](phase-94-pdf-extended-forms.md)、[九十五](phase-95-pdf-automatic-recovery.md)和[九十六](phase-96-pdf-standards.md)分别登记具体矩阵。标准组为 12 份 veraPDF 合规、9 份独立签名验证及 43 页双渲染。自动恢复为 104 次转换、16 次拒绝、1,132 个来源字符与 24 个负控制。表单及交换为 21 份 PDF、4 份数据、15 次拒绝和 11 次旧目标保护。

生产／能力基础检查与 7 组既有 PDF 独立回归通过，包含外部字体、CFF、Form 恢复、扩展字体、Form 编辑、被动结构和复杂页面降级。既有 575 张图像中，仅真实粗体夹具对应的 12 张页面图及两张联络图变化，均补充逐张检查；其余摘要一致。新增图片／文字组的全部 16 张联络图已检查，其他新增组的独立视觉证据由各阶段记录登记。

GAP-10、GAP-11 和 GAP-20 的本表全部子项已验收，本阶段固定的 11 个扩展子项均闭合。GAP-11.2 由生成样例及已有标签文件的两页实际辅助阅读器检查、模型入口的标题／列表／alt／表格及额外 `x` 修复记录，以及完整独立合规／结构／顺序／渲染证据共同覆盖。四页模型的续页末行没有捕获 NVDA 记录，未记作该文件全四页读屏通过；具体覆盖限制见阶段九十六。

## 边界

每项以对应 API 的明确输入组合和标准版本为界；没有新增 OCR、任意 PDF 无损恢复、通用 XFA 或任意 PDF/UA 版本支持。ERROR 默认，WARN／IGNORE 具有相同实际处理并保留损失；权限、签名、非法输入、预算、取消及 I/O 仍硬失败。跨系统／通用阅读器矩阵、长期容量和正式分发继续按既有 GAP-09／14 跟踪，不混入这些子项。

辅助阅读器的实际执行状态单列于[阶段九十六](phase-96-pdf-standards.md#无障碍阅读器)，不得用结构解析代替未执行的交互验收。本地制品验证与远程发布分别判断。
