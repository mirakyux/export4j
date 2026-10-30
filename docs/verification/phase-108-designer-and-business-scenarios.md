# 阶段一百零八：HTML 设计器与完整业务样例

## 验收目标

完成 GAP-13 的两个既定目标：可运行的报告、密集表单、分页长表与引用样例，记录完整调用成本及失败边界；提供独立可选的可视化设计器，实际完成编辑、预览、保存重开和导出。采用 HTML 界面和现有 XML／资源 ZIP，不新增模板格式。

实现和使用见[设计器指南](../designer.md)，模块边界见 [ADR-0106](../adr/0106-optional-html-template-designer.md)。设计器应用不加入 Maven 库发布、BOM、库 SBOM 或公共库 API 基线；核心库不依赖应用。文档生成和转换由既有 Java 引擎完成，浏览器只显示界面和预览。

## 构建与行为

JDK 21.0.10、固定 Gradle Wrapper 及锁定依赖下完成 `build sbom publishLocalBundle :export4j-designer:distZip`，共 1,804 项 Java 测试通过，其中新增设计器测试 12 项。Java 使用 `-Xlint:all -Werror`，本批没有新增第三方依赖。

行为覆盖 JSON 类型／转义／歧义拒绝、XML 外部实体拒绝、混合内容与源码意图、同级排序、嵌套单元格、失败快照保护、撤销重做、未编辑资源及许可保存、显式字体和真实预览几何、ERROR／WARN／IGNORE 的实际损失、取消与旧目标保护，以及回环／令牌／Origin、标签版本冲突、预览失效和路径拒绝。

库 API 对比为 0 个不兼容声明、0 个新增声明、0 个第三方公共类型；兼容脚本的 3 项测试通过。新隔离 Maven 调用方完成 `build run resolvedLibraries`。BOM 与 SBOM 仍包含 16 个库，设计器安装目录及 ZIP 单独生成。无打印依赖验证通过；设计器样例使用 `java.awt.headless=true`，没有启动 Office、打印服务或浏览器进行文档排版。

## 四套完整场景及成本

`DesignerScenarios` 每套保存原始 XML／ZIP、独立 JSON、编辑后的 ZIP、预览和六格式输出。重新打开包后进行实际修改：前三套与引用样例分别产生 `REPORT-EDITED`、`FORM-EDITED`、`LEDGER-EDITED`、`REFERENCE-EDITED`，报告中的修改位于独立绑定数据，变量表达式仍保留在模板内。

| 场景 | 内容 | PDF 页数 | 完整场景耗时 | 结束时观察堆 | PDF 大小 |
| --- | --- | --- | --- | --- | --- |
| 巡检报告 | 中英标题、变量、3 条循环事项、条件、显式图片 | 1 | 4,943 ms | 240.0 MiB | 32,853 字节 |
| 密集静态检查表 | 14 行、正文静态勾选和审核留白 | 1 | 2,612 ms | 163.4 MiB | 32,615 字节 |
| 分页库存台账 | 120 行、跨页重复表头、正文唯一有序 | 6 | 3,771 ms | 262.7 MiB | 36,157 字节 |
| 目录与交叉引用 | 自动目录、标题导航、题注编号、最终附录页引用 | 3 | 2,175 ms | 134.4 MiB | 27,164 字节 |

完整场景耗时包括源码／资源包保存重开、实际编辑、预览和六种实际编码。运行环境、冷启动及缓存影响数值；观察堆是场景结束时采样，既不是同时峰值，也不是 RSS 或容量保证。逐格式耗时、大小和损失数保存在生成的 `report.json`，本批单次格式编码为 27～1,026 ms。

24 份输出覆盖 PDF、DOCX、HTML、Markdown、SVG 页 ZIP 和 PNG 页 ZIP。引用场景中 PDF 无损失；DOCX、HTML、Markdown 分别报告 4、5、9 项损失，SVG／PNG 各报告 1 项交互对象丢弃。静态检查表的 `[x]` 是正文文本，不将它称为可填写字段。

11 项实际拒绝／失败控制为：非法 XML、外部实体、未知模板节点、绑定缺值、JSON 重复键、JSON null、预取消、源码预算、严格导航导出、不受支持的静态处理方式，以及输出超限后旧目标逐字节保持。只有所有场景及控制完成才写入成功报告。

## 独立输出证据

固定 Python 验证依赖与 Poppler 下，`scripts/verify_designer.py` 完成：

- 普通 ZIP 成员、原始模板与未编辑字体／图片／许可的字节核对；变量、条件、循环和独立数据编辑保持。
- pypdf、PDFium 各自提取正文，检查四套修改标记、末尾标记、条件分支、表单 14 行，以及台账 120 行恰好一次且有序；每页表头和附录最终页码 3 单独检查。
- PDFium 逐字符页面边界；独立 OOXML XML 检查表头和引用降级占位；HTMLParser 与 markdown-it 解析实际正文和嵌入图片。
- 11 页 PDF 全部经 Poppler 与 PDFium 192 DPI 双渲染；SVG ZIP 全部成员经 resvg 渲染，PNG ZIP 全部解码并与独立 PDF 图像比对，页数、尺寸、内容与相应像素阈值通过。
- 密集网格采用已有表单验收的 192 DPI／blur2 阈值。实际比较图确认 Poppler／PDFium 的字形边缘和细网格栅格化差异，未发现内容位移或缺失；正文及字符几何另有独立断言。全部 11 页联系图逐页复查，未刷新任何视觉基线。

独立结果为 4 个场景、24 份导出、PDF／SVG／PNG 各 11 页及 11 项失败控制通过。

## HTML 界面实测

本轮在 Windows 的 Chromium 内嵌浏览器对真实回环应用操作，完成：画布选择段落、编辑正文并重新预览、撤销恢复原文、重做恢复编辑、插入段落及上移、下载实际编辑的模板 ZIP 和独立 JSON、重新选择两份下载文件并恢复绑定预览，以及 PDF 实际预检／下载。下载的 ZIP 独立检查保留源码变量／循环／条件和插入、编辑内容；下载 PDF 独立提取确认修改内容恰好一次。

HTML 严格导出遇到 `PAGE_GEOMETRY` 拒绝，下载按钮保持禁用；WARN 预检报告 1 项损失／1 项警告，IGNORE 仍保留 1 项损失而警告为 0。非法源码节点应用被拒绝，原工作区可继续使用，恢复草稿后重新预览成功。

密集表单单元格通过结构树修改并重新预览。最终分发 ZIP 实际解包启动后，浏览器完成图片上传及命名、插入图片、取消删除保持内容、确认删除及撤销恢复；未保存新建提示分别核对取消保留与确认切换。目录引用样例从第 1 页依次切换至第 2／3 页，核对预览图加载和第 3 页附录的画布选中及属性。

浏览器测试发现 Windows XML 序列化 CRLF 与文本框 LF 导致一次编辑多生历史记录，已通过规范化比较修复，并实际复验撤销／重做。图片命名和操作确认改用页面内对话框，避免依赖内嵌浏览器不支持的原生提示框。文件选择控件在异步操作期间保持可触发；预检缓存会在文档或配置修改时失效。上述界面证据只对应本轮浏览器，不替代 GAP-14.2 的其他平台／阅读器矩阵。

## 复现

从仓库根目录运行，Windows 使用下列命令，其他平台替换 Wrapper。先准备[固定验证依赖](../../scripts/requirements-verification.txt)及 Poppler。场景目录必须未存在，重复运行时更换目录名。

```powershell
.\gradlew.bat --no-daemon --console=plain --gradle-user-home build/gradle-validation build sbom publishLocalBundle :export4j-designer:installDist :export4j-designer:distZip
.\gradlew.bat --no-daemon --console=plain --gradle-user-home build/gradle-validation :export4j-designer:scenarios -PdesignerOutputDirectory=export4j-designer/build/scenarios-complete
python scripts/verify_designer.py --source export4j-designer/build/scenarios-complete
python scripts/test_api_compatibility.py
powershell -NoProfile -File scripts/verify_local_consumer.ps1
python scripts/verify_driverless.py --consumer-build-directory <consumer-build-directory> --output-directory build/verification/designer-driverless
.\gradlew.bat :export4j-designer:run
```

`<consumer-build-directory>` 使用本次消费者脚本写入 `build/verification/consumer-build-directory.txt` 的目录。原始日志、截图、下载检查及独立图像位于忽略的 `build/verification/`，场景源码和输出位于 `export4j-designer/build/scenarios-complete`，不随项目源码提交。

## 当前状态

GAP-13 的既定设计器和四类完整样例目标已验收。当前未完成的独立条目为 GAP-14.2、GAP-14.3、GAP-22；OCR 仍为范围外。应用具有[明确预算和编辑范围](../designer.md#预算与使用范围)，不是任意外部文档图形编辑器或多人服务。没有执行正式签名、Central 上传或正式发布。
