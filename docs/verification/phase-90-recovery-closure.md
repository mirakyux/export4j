# 阶段九十验收：GAP-24 自生成文档恢复闭环

本阶段完成 GAP-24.1～24.4 所列的已支持声明模型及目标子集验收。新增声明式导航持久化，组合分节、页区变体、图层、嵌套合并表、图片、注释和引用，完成实际编辑、多轮转换、原包／原页面保留及真实 Word 保存回读。完成边界见 [ADR-0093](../adr/0093-portable-navigation-and-recovery-acceptance.md)；任意外部文档识别、运行时代码序列化及更多原对象编辑仍分别归各自独立缺口。

## 四项交付

| 分项 | 本阶段补齐及既有证据 |
| --- | --- |
| GAP-24.1 | `DocumentNavigation` 保存当前顶层标题筛选／最大级别或手工书签，以及目录文字、完整条目排版、可选标题／页码排版和显式颜色。XML／恢复信息保留声明意图，恢复后引擎自动根据当前标题及最终分页生成目录／书签。原有模型、注释、引用、页区及资源保存继续有效 |
| GAP-24.2 | 回归七种标准输出的显式伴随／可选内嵌、准确字节／资源绑定及损坏拒绝；补齐 Word 实际编辑、Save、关闭、重开，再按 ERROR／WARN／IGNORE 回读当前文件。普通输出仍无内嵌开销 |
| GAP-24.3 | 复杂 DOCX 无编辑保留准确字节，局部改字只改变 `word/document.xml`；四页 PDF 改字保留未选页像素、表格、页区、图片及被动 ActualText，旧恢复包失效。标记外普通文字可编辑，标记内部仍明确拒绝或跳过 |
| GAP-24.4 | 四格式各三轮目标保存、每轮 XML 控制、正文／标题／格内／注释实际编辑，以及 16 组 A→B→A。独立检查当前文件／模型／ID／资源与累计损失，结合此前七格式 49 组、分节 36 组、注释引用 16 组矩阵完成分组覆盖 |

## 模型与 API

[声明式导航](../portable-navigation-api.md)属于模型，布局层负责转为运行时导航。它不保存字体句柄、Java 回调、已解析页坐标、运行时评论或可编辑表单配置。自动标题始终读取当前正文；手工标题是字面值，目标删除须在同次编辑显式修复。模型导航与非空运行时导航冲突时拒绝。

标题／页码样式和颜色按声明是否存在保存，显式黑色与默认黑色保持区别。目录文字计入模板绑定、编辑与 Web 导出的共享预算；手工目标、层级、Unicode、未知 XML 结构和预算错误不能靠损失策略放行。无法返回导航的旧 `render`／`renderWithPageRegions` 入口，以及返回 `Document`／`PageDocument` 的编辑入口均明确拒绝非空导航，完整模型使用 `renderSections`／`renderComplete`。

`SectionedDocument` 两参数构造器及 `DocumentTemplate` 既有构造器保留，默认无导航；新增 record 组件需要快照调用方重新编译。无导航旧 XML 不增加节点；新语法需要相应版本读取器。语义编辑同时补齐独立 `CrossReference` 字面前缀替换，保持类型／目标／格式。

## 组合语料与损失

[RecoveryClosureExample](../../examples/src/main/java/com/mirakyux/export4j/examples/RecoveryClosureExample.java)使用 420×420 pt 两节、24 pt 边距、24 pt 页区及 4 pt 间距。正文四页，自动前置目录一页，显示页码从 2 和 8 重新开始。语料包含共享页区、FIRST／EVEN 及显式空变体、共享蓝色背景图片与紫色前景、两层表格及纵向合并格、行内图片、脚注／尾注、题注、编号、行内及独立页码／编号引用，保留字面 `${literal}`。

显式字体为 Liberation Sans Regular／Bold，附许可；字体角色由调用方明确注册。图层保留彼此遮挡关系，组合示例将其放在脚注分隔线之上；固定图层本身不预留正文空间。

| 目标 | 首次保存总损失 | WARN 警告 | IGNORE 警告 | 恢复结果 |
| --- | ---: | ---: | ---: | --- |
| PDF | 0 | 0 | — | 两节、两条注释、导航声明、原格式和引用完整保持；目录及书签使用编辑后的标题 |
| DOCX | 14 | 14 | 0 | 从实际文件保留两节、嵌套表格和已支持原生行内字段；明确丢弃导航／注释／图层，降级题注与独立引用 |
| HTML | 519 | 519 | 0 | 当前文件重导入为一个流式节；页区／图层成为普通流内容，图片变为替代文字，动态字段为未解析文字，注释及导航不返回 |
| Markdown | 108 | 108 | 0 | 当前文件重导入为一个流式节；分页／嵌套表／字段及图片按已验证规则降级，注释及导航不返回 |

有损数值属于固定语料，包含导出、一次 `MODEL_RECOVERY / CURRENT_FILE_REIMPORT` 和真实导入损失。WARN／IGNORE 产生相同标准字节及模型，IGNORE 仅不呈现警告；ERROR 在准备阶段拒绝。三轮和 16 组矩阵均保持历史损失单调累计，只有 PDF→PDF→PDF 保持本组合完整模型。SVG／PNG 静态后端对该交互语料明确拒绝；其不含交互的恢复路径继续由既有七格式语料验证。

## 原页面编辑与浮点修复

PDF 文字编辑原先因页面含任意 BMC／BDC 而整页拒绝，连图片锚点之外的普通正文也无法修改。现在预先验证平衡、有界的 `/Span BMC` 及仅含字符串 ActualText 的 `/Span ... BDC`，保留命名 Properties 与嵌套标记，允许编辑标记外普通文字。命中标记内显示文字或逻辑文字使用 `MARKED_TEXT / SKIP_MATCH`；未知标签／属性仍为 `TEXT_STRUCTURE / SKIP_BLOCK`，畸形结构无条件失败。

独立像素比较证明四页中的其余三页不变，选中页差异只在目标文字带，原嵌入字体、标记、图像、表格和页区保持；旧伴随包拒绝。文字替换不等于脱敏；既有新建纯图片 PDF的脱敏回归同时通过。

真实 Word 保存的表格宽度还复现了布局缺陷：`110.7 + 80.65` 的二进制结果略大于父格可用 `191.35` pt，旧代码误报最小列宽超宽。修复复用既有单 ULP 几何边界，保留固定列宽，不缩放；测试先复现失败，再证明贴边通过、实际超宽 0.01 pt 仍拒绝。

## 真实 Word 保存与回读

[verify_recovery_word.ps1](../../scripts/verify_recovery_word.ps1)只复制生成样例，在有界私有 Word 进程内遍历正文及页区故事，替换实际文字、Save、关闭、重开并验证修改，按文件摘要绑定证据；原有应用进程保持。实际 Word 版本为 16.0，遍历 15 个故事。

本语料经 Word 保存后，`customXml/export4j-recovery.xml` 被移除。默认 ERROR 拒绝；WARN／IGNORE 明确受限导入当前 DOCX，均返回 `recovered=false`、400 条实际损失，警告数分别为 400／0。当前标题、正文、共享页眉及嵌套格内的 `WORD-EDITED` 保持，两个节／两层表格／图片文字仍可重导出，旧 `ORIGINAL` 和目录声明没有回来。

Word 不提供原固定页区高度元数据，调用方显式配置 `DocxAdapter.RegionGeometry(24, 4, 24, 4)`，不按内容猜高。Word 重建页区节点 ID，保存为 XML 时另记录 ID 规范化损失，再从新 XML 的实际模型建立恢复信息。Word 保存还改变表格列宽／换行和列表编号，并使部分页区字段按当前缓存文字读取；回读 PDF 可出现 `PAGE=0/1`。这些是实际受限导入损失，不承诺原版外观或动态字段保持；没有从旧恢复数据补回。

此次证据是生成的 DOCX→Word 编辑／保存→当前 DOCX 受限导入→XML／PDF。未验证其他应用版本、任意文档或所有阅读器；外部兼容扩展继续归 GAP-14.2，外部结构识别与编辑归 GAP-10／12／18／20。

## 可选内嵌体积

组合样例初始文件的实际字节数如下。内嵌增加的是模型、两种显式字体、许可、资源及封装；比例随文档和压缩效果变化，不能当作固定成本。

| 格式 | 普通文件 B | 内嵌文件 B | 增量 B |
| --- | ---: | ---: | ---: |
| PDF | 51,881 | 475,555 | 423,674 |
| DOCX | 11,353 | 582,436 | 571,083 |
| HTML | 18,673 | 593,831 | 575,158 |
| Markdown | 2,133 | 574,279 | 572,146 |

七容器的小样例体积与读取规则继续见[阶段八十六](phase-86-standard-container-recovery.md)。伴随 ZIP 将恢复开销放在独立文件，普通导出不携带隐藏恢复副本。恢复数据是明文，分发权限、字体许可和敏感内容由应用统一管理。

## 验证结果与复现

Java 21、Gradle 9.2.1；独立工具使用 `scripts/requirements-verification.txt` 固定版本：pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0、python-docx 1.2.0，PDF 分别由 Poppler 26.07.0 与 PDFium 以 192 DPI 渲染。

- 全仓 **1,516 项测试，零失败／错误／跳过**；新增回归覆盖导航声明意图、绑定／编辑、预算、非法 XML、运行时冲突、真实目标损失、引用前缀、被动标记及贴边列宽。
- `build sbom publishLocalBundle` 通过；新隔离目录使用发布的 Maven 坐标完成标准输出及所有恢复调用方，15 个自研库、15 条 BOM 约束、23 项运行时 SBOM 清单核对通过。
- 新组合验证 **12 份恢复文件、16 组矩阵、66 页 PDF 双渲染**；11 张全页联系图逐页查看，核对目录、正文、脚注、页区、图片、表格及有损重排。没有更新视觉基线。
- 独立回归 model／complex／section／navigation recovery、四组 embedded recovery 和 PDF content editing 均通过；沿用各自明确子集，不将回归结果扩大为所有外部文件兼容。

以下命令从仓库根运行，Windows 使用 `.\gradlew.bat`，其他平台使用 `./gradlew`。Python 使用已按锁定依赖准备的隔离环境；`<consumer-build-directory>` 是本轮新隔离调用方目录。

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:recoveryClosure :examples:pdfContentEditing --no-daemon
pwsh -File scripts/verify_recovery_word.ps1 -Source examples/build/recovery-closure/docx/embedded-original.docx -OutputDirectory examples/build/recovery-closure/word
.\gradlew.bat :examples:recoveryClosureExternal --no-daemon
.\gradlew.bat -p verification/consumer "-PconsumerBuildDirectory=<consumer-build-directory>" clean build run resolvedLibraries recoveryClosure modelRecovery lossyRecovery sectionRecovery navigationRecovery embeddedHtmlRecovery embeddedCommentRecovery embeddedPngRecovery embeddedContainerRecovery --refresh-dependencies --no-daemon
python scripts/verify_recovery_closure.py --consumer-build-directory verification/consumer/<consumer-build-directory>
python scripts/verify_pdf_content_editing.py
```

组合示例调整图层位置后重新生成并重新执行调用方及组合独立检查；Word 源 DOCX 字节不变，既有实际保存证据摘要继续匹配。完整结构报告、页面及原始日志保留在忽略的 `build/`，公开文档只记录可复现契约及结果。
