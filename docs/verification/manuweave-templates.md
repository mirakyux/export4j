# ManuWeave 学术模板与数学公式验收

## 范围与来源

[ManuWeave README](https://github.com/jiassia/manuweave/blob/e48d7b6d571e133d0bc86fc7cf61b343fa9cb64b/README.md) 所列的全部 286 个 Profile ID 已接入 `:examples:manuWeave`：277 个期刊／出版社 profile 和 9 个通用 preset。源快照固定为 `e48d7b6d571e133d0bc86fc7cf61b343fa9cb64b`；分组、运行方法和许可见[模板说明](../../examples/src/main/resources/templates/manuweave/README.md)。

Java 渲染器及虚构稿件由 Export4j 编写，不执行或移植上游实现。独立核对覆盖全部 ID、分组、顺序、来源 URL、286 个原始 JSON 的 SHA-256 和 MIT 原文字节。保存的源事实共 70,376 个叶子：39,663 个原布局事实、27,310 个提取格式事实和 3,403 个源元数据叶子。计数包含数值、单位及来源说明，不代表相同数量的独立排版规则。新增保存的引文／参考文献／公式事实共 26,949 个叶子；独立核对未调用同步脚本内部函数。

```powershell
python scripts/sync_manuweave_catalog.py --source <固定上游快照目录> --check
```

## 本轮实现

| 项目 | 实际行为 |
| --- | --- |
| 字体 | 明确区分 Liberation Serif／Sans 及四种真实变体，中文使用固定文楷正常体；外部 XML 可提供已授权源字体，缺少请求变体或物理样式不符即失败 |
| 分页 | 标题与后续内容、表题与表格、图形与图题、参考文献标题与三条条目整组保留；普通段落使用 2／2 最低行数，连续栏有界平衡 |
| 页区 | 根据源配置生成默认／首页／奇偶页区、最终 PAGE／PAGES、距离与字体；实际字体和字段行盒决定显式预留，报告保留源事实与有效值 |
| 镜像边距 | 117 个明确启用的配置按显示页码交换左右边距，在最终 Display List 中进行物理映射，不重新换行或分页 |
| 审稿行号 | `comp-jasa-word` 和 `comp-jslhr-word` 按实际正文基线连续编号；表格、数学轮廓及页区不冒充正文行 |
| 参考文献 | 根据源事实选择数字、作者年份等有限格式，应用顺序、作者、标题及年份位置；报告实际模式、来源和局限 |
| 数学 | 模板公式实际排为矢量，并按源声明对齐本体；专项另有 8 类 PDF／SVG 公式和 8 条 Word 原生 OMML 公式 |

页区文字使用虚构标题和作者，不推断模板未提供的真实出版物文字。原纸张和边距保持来源；例如 FCS 的零上／右边距属于固定上游数据，不以验收为由改变源配置。

## 生成与独立验证

本轮生成 286 份 PDF、286 份报告及 286 份原文，共 981 页，每份 2～8 页。使用 JDK 21、固定 Wrapper 和项目隔离 Python 环境；独立工具为 pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0、fonttools 4.62.1、Poppler 26.07.0。

```powershell
.\gradlew.bat --max-workers=1 --no-parallel :examples:manuWeave
python scripts/verify_manuweave.py --jobs 4 --output build/verification/manuweave-v2
```

验证器独立核对源配置、原文完整性／唯一性／顺序、真实字体／字号／颜色／行距／缩进、纸张／栏几何、嵌入字体、图像、公式矢量及 ActualText，同时检查题注相邻、最低行数、页区、最终字段、镜像边距、连续行号、参考文献及公式本体对齐。默认验收显式内置字体配置，调用方外部映射需要另行验收。

每个选中页面由 Poppler 和 PDFium 独立渲染。协议保持 192 DPI、2 像素模糊、平均通道绝对误差小于 4、变化像素比例小于 0.06；没有放宽阈值或自动刷新视觉基线。PDFium 使用精确 DPI 变换，避免整数画布引入额外缩放。

全部 286 份、981 页通过结构与双渲染检查，最大平均误差为 3.159773，最大变化像素比例为 0.017834。检查覆盖 751 个页区和 254 条连续审稿行号。目视检查发现匿名审稿偶页页眉泄漏虚构作者，已修正页眉和作者元数据；三个匿名配置的最终 10 页重新完成结构、双渲染与目视检查，再以当前 PDF SHA-256 替换主报告中的对应记录，保留旧报告。

全部页面的整页联系表已实际查看，分为 1～100、101～150、151～200、201～286 四组，共 354、126、153、348 页。最后的匿名补验覆盖旧图，不以旧图证明修正后的输出。没有观察到阻塞性的裁切、遮挡、缺图或图表结构脱离；部分完整尾页留白仍单独登记。视觉清单与主报告逐项核对当前 PDF 摘要及页数，不能把此结果解释为原刊版面逐像素复刻。

最终版本的九个破坏性负控制均被拒绝：错误 TeX、删除公式矢量、删除审稿行号、删除偶页镜像、错误 PAGES 终值、单行正文片段、公式整体平移 12 pt，以及匿名作者元数据／页区泄漏。完整输出及报告位于忽略目录 `build/verification/manuweave-v2/`，源核对、代表、负控制及视觉证据位于相邻的 `manuweave-v2-source/`、`manuweave-v2-representatives/`、`manuweave-v2-controls/` 等目录。

当前分发包的 `--list` 返回全部 286 个 ID，顺序与目录一致。未知／重复 ID、错误选项组合、字体映射参数及真实样式错误等 13 项命令行负控制均以非零状态退出，并保留全部 858 项既有输出摘要。错误上游快照也在写入前被拒绝，290 项目录资源保持。

## 数学与实际 Word 验收

```powershell
.\gradlew.bat :examples:manuWeaveMath
python scripts/verify_manuweave_math.py
powershell -NoProfile -File verification/verify_docx_math_word.ps1 -InputPath examples/build/manuweave-math/math.docx -OutputDirectory build/verification/manuweave-math-word
.\gradlew.bat :examples:manuWeaveMath --args="--verify-word-saved build/verification/manuweave-math-word/word-saved.docx"
python scripts/verify_manuweave_math.py --word-directory build/verification/manuweave-math-word --consumer-build-directory <本次成功隔离调用方目录>
```

专项涵盖分式／根式、上下标、积分、求和、矩阵、分段、希腊字母／三角函数及极限／连乘。PDF／SVG 为一页矢量输出，115 个轮廓、2,485 个三次曲线段、零公式图片，保留原始 TeX ActualText。PDF 双渲染的平均误差为 0.224091、变化像素比例为 0.000599；页面已视觉查看。

`math.docx` 的 8 条公式使用标准 OMML，含两个矩阵结构，无图片替代。去除全部项目私有元数据后三轮读写保留公式类型及设置；Java 实际把首分母 `2a` 改为 `3a`，其余七式和源文档保持。未知数学语法、安全边界、预算、取消、锚点及输出失败由专项测试覆盖。旧普通／原生语义读取入口在两种损失策略下都拒绝数学内容，避免静默丢弃。

补充检查发现并修正了极小节点预算下的往返不对称：隐藏的空根指数／上下限只作为 XML 占位，读写都不计入实际模型节点；可见空序列仍计费。三项回归同时验证相同预算的往返、可见空参数计费及两种策略下对隐藏非空／畸形内容的拒绝，独立包预算保持。

实际安装的 Word 16.0（build 16.0.20527）在自有隐藏实例中打开该文件，保留 8 条原生公式，把第一条分母 `2a` 改为 `5a`，核对其余七式未变，保存、关闭、重开并导出一页 PDF。过程核对独立进程所有权，不复用或关闭用户的 Word；这是应用自动化验收，不是人工界面操作。

Word 保存包用 `ALLOW_LOSS` 回读，返回 75 项普通样式／主题／注释等未映射属性诊断；数学类型及数学设置保持，重建包再以严格模式读取通过。Word PDF 经全页双渲染和视觉检查，平均误差为 0.287317、变化像素比例为 0.001099。收据及 Java 回读报告通过 SHA-256 绑定当前输入、保存包及导出 PDF，旧证据不能代替本次文件。

数学机器报告位于 `build/verification/manuweave-math/` 和 `build/verification/manuweave-math-word/`。API、预算、流所有权及无损范围见[数学指南](../docx-math-api.md)；架构见 [ADR-0108](../adr/0108-native-docx-math-and-example-typesetting.md)。

## 构建、调用方与依赖

```powershell
.\gradlew.bat --max-workers=1 --no-parallel build sbom publishLocalBundle :examples:textPdf :examples:flowPdf :examples:distZip
.\gradlew.bat -g build/gradle-validation --max-workers=1 --no-parallel -p verification/consumer -PconsumerBuildDirectory=<新的相对构建目录> clean build run nativeMath docxSemanticComplete resolvedLibraries --refresh-dependencies --write-locks --no-daemon
```

全仓 1,925 项 JUnit 测试零失败、零跳过；Javadoc 和编译警告门禁通过。API 检查为零不兼容声明、83 项新增、零第三方类型泄漏；API 检查器的三项行为测试通过。首次默认并行构建的四个测试 JVM 因 Windows 提交内存不足启动失败，降低并发后完整重跑成功，未修改系统配置或放宽断言。

本次全新隔离调用方从本地 Maven 制品创建分式和矩阵，严格往返并把 `2a` 改为 `3a`，其他公式及源模型保持；已解析依赖不含 examples 或 JLaTeXMath。既有 DOCX 原生语义组合调用方也执行成功。无打印验收检查 16 个生产 JAR 和 31 个解析运行制品摘要，三类打印 API 负控制被拒绝；普通、DOCX、HTML 及新增原生数学调用方均在禁止打印类加载的无桌面 JVM 中执行成功。第一次沙箱临时目录写入失败已保留，显式使用工作区临时目录后通过，不改动打印机、驱动或服务。

数学预算修正后，使用全新 `verification/consumer/build/validation-manuweave-v3/` 重新执行上述调用方及 DOCX 独立结构检查，并刷新 Word 保存包的 Java 回读证据。当前无打印报告位于 `build/verification/manuweave-v3-driverless/`，含原生数学的独立实际执行收据；当前分发包的 13 项命令行负控制也重新执行通过，858 项模板输出保持不变。

既有 production 与 capabilities 示例重新生成并通过各自独立结构／双渲染验证；未据此扩大 PDF/A、可访问性或可选外部签名工具的支持声明。

当前示例分发包的 290 项模板资源与源码逐字节相同，四种 Serif 字体及五份数学原始法律声明完整。JLaTeXMath 主 JAR、sources JAR 摘要、三个原始构建 POM 均核对通过，未引入 Greek／Cyrillic 文本字体附加包。当前 17 个库／BOM POM 和库运行 SBOM 均不包含 JLaTeXMath。其原始链接例外、数学字体条款和源代码分发义务见[依赖说明](../dependencies.md)。

本轮另以 `0.0.0-verification-manuweave-v2` 和临时验证密钥执行本地签名演练；数学预算修正后已使用新目录重新执行：17 个模块、82 份制品签名、16 项 BOM 约束及库 JAR／SBOM 摘要核对通过，篡改签名被拒绝。独立复制的调用方解析全部 16 个库及 BOM，实际 JAR 摘要与签名仓库一致；临时私有 keyring 已清理。最终证据位于 `build/verification/manuweave-v2-budget-signed/`，早先报告保留作过程记录。这是本地测试身份，不是正式签名或远端发布；没有提交、推送或上传。

## 边界

已授权原字体可由调用方显式提供；内置替代不宣称与 Times New Roman、宋体、Libertine 或 STIX 字形一致。公式的有限 TeX 排版位于示例作用域；原生 Word 数学使用自研类型树及既有 JDK XML／ZIP，无需数学库、TeX 安装或 Office。

本轮未提供通用跨格式数学模型、自动 TeX↔OMML、完整 CSL／BibTeX、任意 LaTeX 类文件执行或全部 286 模板的 Word 输出。文字参考文献子集不应用完整刊物样式、卷刊强调、DOI 生成、引文压缩或页脚参考文献放置。Word 自动行距、文档网格、专属图形及数学扩展继续按实际报告和 API 边界处理。有限模板、公式与应用动作不构成投稿合规认证、任意 Word 无损兼容或正式发布。

整组保留和最低行数约束避免题注脱离及孤行，但未消除所有稀疏尾页。例如 `computer-ieee-92-original-research-and-brief`、`computer-elsevier-artificial-intelligence` 和 `computer-wiley-spe-manuscript` 的最终页面仍有较多留白，内容完整；本次不以压缩原字号、行距或修改源纸张／边距来填满页面。
