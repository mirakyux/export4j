# 阶段八十三验收：HTML 单文件模型恢复

本阶段推进 **GAP-24.2 的 HTML 内嵌恢复子目标**，同时扩充 GAP-24.4 的单文件往返证据。此前必须同时传递 HTML 和恢复 ZIP；现在可显式把相同模型／资源包放入普通 HTML 末尾注释，再由单个 `.html` 重新打开、编辑和导出。未新增专用扩展名、模块或运行依赖。

## 行为与边界

`XmlModelRecovery.openEmbeddedHtml`、`Saved.embeddedHtmlBytes` 和两个 `writeEmbeddedHtml` 重载复用已有完整语义 XML、资源摘要、三动作及累计损失。注释外的 HTML 字节准确绑定；外部改字后严格模式拒绝，WARN／IGNORE 只有显式 fallback 才读取当前页面。fallback 不接收旧注释，不能把旧模型带回；有损捕获仅输出普通 HTML。

总文件字节、Base64 解码 ZIP、展开条目及 XML 均受限。重复／截断／非法 Base64、损坏 ZIP、资源摘要冲突、预算、取消和 I/O 不受忽略策略放行。路径失败保护旧目标，借用输出不关闭、不刷新。具体存储契约见 [ADR-0088](../adr/0088-embedded-html-model-recovery.md)。

## 可复现入口

Java 21、Gradle Wrapper；Python 3.12.14、pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0 和 Poppler 26.07.0。仅复用已登记的 Liberation Sans 字体及许可。

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:modelRecovery :examples:embeddedHtmlRecovery --no-daemon --console=plain
```

按[隔离调用方流程](../../scripts/verify_local_consumer.ps1)生成新的成功构建目录；在同一调用方运行 `modelRecovery embeddedHtmlRecovery resolvedLibraries` 后执行：

```powershell
python scripts/verify_embedded_html_recovery.py --consumer-build-directory <本次成功的调用方构建目录>
```

旧伴随包回归另外运行 `scripts/verify_model_recovery.py --prepare-external`、`:examples:modelRecoveryExternal` 及 `scripts/verify_model_recovery.py --consumer-build-directory <本次成功的调用方构建目录>`。样例在 `examples/build/embedded-html-recovery/`，独立报告及渲染在 `build/verification/embedded-html-recovery/`；不以旧调用方目录代替当前构建。

## 实际结果

| 层次 | 结果 |
| --- | --- |
| 构建与测试 | 全仓 `build sbom publishLocalBundle` 通过；1,460 项测试，0 失败／错误／跳过。6 个新增行为案例覆盖三轮模型／资源、真实改字、外部改字、缺失／未知版本、损坏／重复封装、有损隐藏禁止、预算、取消、借用流和原子目标 |
| 单文件与独立结构 | 7 个内嵌 HTML（含调用方和外部修改控制），Python 标准 HTMLParser／Base64／ZIP／XML 独立检查；注释外 HTML 与普通输出准确一致，CRC、清单及模型／字体／许可摘要一致 |
| 三轮与真实修改 | 三轮后 HTML／模型源码一致；ORIGINAL 改为 EDITED，调用方另改为 CALLER；新源码不含旧词及未使用模板。外部修改控制的当前页面为 EXTERNAL，旧摘要准确失效 |
| 三动作 | ERROR 拒绝过期数据；WARN／IGNORE 显式导入当前 HTML，损失数量一致，IGNORE 无当前警告；新内嵌模型不含 ORIGINAL。损坏与预算失败不调用 fallback |
| PDF | 控制、实际编辑及 Maven 调用方共 3 页，经 pypdf 文字／顺序核对、Poppler／PDFium 双渲染及全部逐页查看；改字产生实际像素变化，未修改的第二行及后续区域像素保持 |
| 浏览器 | 内置浏览器实际查看普通／内嵌 HTML 在 1000px 和 375px 宽的框架页面，正文一致；恢复注释不显示，编辑结果显示 EDITED。没有把浏览器查看称为编辑器保存回读 |
| 本地制品 | 新 Maven 坐标调用方构建、通用调用、既有伴随包恢复及 HTML 单文件恢复／编辑／重导出通过；23 个运行组件、15 个自研库和 15 项 BOM 约束一致，调用方实际分发的 15 个自研 JAR 的 SHA-256 全部匹配本次 SBOM |
| 既有恢复回归 | 独立验证 73 个伴随包、56 行矩阵、49 组 A→B→A、七种格式及七方向外部工具重写失效；16 页 PDF 结构、文字与双引擎渲染通过 |

## 尚未覆盖

本阶段只验收既有有限语义模型的 HTML 内嵌，不关闭其他格式内嵌、一般复杂跨格式、浏览器／编辑器重存保留或任意网页无损导入。内嵌资源仍可读取、仍需许可管理；SHA-256 仅验证一致性。普通 HTML 的显示字体与恢复后 PDF 的显式字体保持各自契约。
