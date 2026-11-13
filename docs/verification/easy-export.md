# 统一导出入口验收

本记录用于当前开发版 `export4j-starter`，验证字体／样式配置一次后直接从文本、Markdown、HTML 或正文 XML 生成 PDF／DOCX。它不属于已发布 RC3，也不继承上一批 17 个库的签名与渲染结果。

## 本批状态

2026-11-13 完成本地构建、公共 API、18 库／BOM 签名分发、完整及单依赖消费者、独立输出检查和逐页视觉复查。候选版本为 `0.0.0-verification-e2`，签名使用临时验收身份。这是修正前的历史基线；下方 e2 结果与摘要不代表 2026-11-14 修改后的制品，也不代表 Maven Central 已提供该模块。

2026-11-14 修正主题语义域传递与 DOCX 字体声明校验。候选版本 `0.0.0-verification-e3` 的全量构建、公共 API、签名分发、四路隔离消费、统一入口及主题专项独立输出复验均已通过。本轮未新增或启用回退，也未改变现有预算；截至该次本地验收完成时尚未提交、推送或远程发布。

## 已知限制

- 后续接入核对发现：公式文字字体合计超过 64 MiB 时，字体预算异常被错误归类为 `RENDERING_FAILED`。使用本批 e3 制品复现：默认无候选时仍严格失败；显式配置 `TEXT` 候选时会错误降级成功。该错误分类尚未修复，不满足预算超限必须为 `LIMIT_EXCEEDED` 且禁止降级的契约；本批其余验收结果不涵盖这一失败路径。
- starter 尚无任意具名字体角色的登记 API。HTML 可按已声明的 `font-family` 选择字体；Java／XML 的业务角色仍须通过底层 `DocumentEngine.fontRole(...)` 登记，不应依赖 starter 内部生成且随字体族顺序变化的 `family_N` 名称。
- 本批隔离消费者不代表业务项目已经迁移。现有 RC3 使用端需要接入新制品，并对其真实模板、字体、授权图片和容量配置重新验收；正文 XML 快捷入口不覆盖页区、分节及注释。

## 2026-11-14 修正结果

| 检查 | 本轮结果 |
| --- | --- |
| 根因修复 | 完整语义样式快照贯通主题、源内容和数学编译；六级标题与单元格不再套用正文外观；公式在最终宿主样式确定后绘制 |
| DOCX 字体 | 普通正文与列表标记只校验角色声明，允许物理字体不含文字字形；PDF 仍严格失败且保留旧目标；不逐字改写 DOCX 回退字体 |
| 构建与测试 | `build sbom publishLocalBundle`、公共 API 与相关样例通过；2,693 项 Java 测试，0 失败、0 错误、0 跳过，比历史基线新增 18 项有效回归 |
| API 与分发 | 804 个公共类型，0 不兼容声明、105 项新增、0 第三方类型泄漏；18 库及 BOM、92 项签名制品与篡改拒绝通过；719 项签名输入与当前源码一致 |
| 四路消费 | 完整依赖和仅 starter 分别通过 Gradle 元数据／仅 POM 两路；仅 starter 为一个直接依赖、无 BOM、14 个自研模块、26 个运行制品、10,459 个 Java 21 有效类、0 重复提供者；各 JAR 哈希与签名仓库一致 |
| 原统一入口样例 | 示例和四路消费共 25 份 PDF、20 份 DOCX，通过结构与双引擎检查；独立阅读器实际打开 4 个唯一 DOCX，其他 16 份按准确字节相同复用；四路 PDF 共 40 张渲染图与已查看示例逐像素相同 |
| 主题专项 | 四路各七组，共 28 份 PDF／44 页与 32 份 DOCX 通过结构检查；28 份 DOCX 实际独立打开并生成 40 页，另 4 份仅验字体声明；字号、颜色、公式轮廓、原生 OMML 与原目标保护通过；84 页分别经 Poppler／PDFium 渲染 |
| 主题视觉复查 | 首路全部 7 张联系表、42 张页面渲染图已查看；其他三路对应图像 126 项逐像素相同；各路 Markdown／XML 的 PDF 和 DOCX 阅读器输出同引擎像素一致；未见缺字、裁切或公式覆盖 |
| 配套回归 | 源内容 Java／XML 双页样例、公式 PDF／SVG／PNG 与原生数学通过独立检查；无打印运行的 8 个消费者与 3 个负控制通过；文档准备 7 项、API 检查器 6 项、发布流程 9 项和项目规则检查通过 |

本轮本地坐标为 `com.mirakyux.export4j:export4j-starter:0.0.0-verification-e3`，制品目录为 `build/verification/e3-final/repository/`。starter JAR SHA-256 为 `39e6faf4cdcabb5c803ee20807f7d144cb1344da850552d319db2a530b75bec4`；分发 ZIP 为 `f6a8a7d60ffe937ff5ea15e9f1fbae8bbcb59c74af4a55605f8ef835cfa4e622`；SBOM 为 `2793c050d1a522411546c4894c8fef8f5dddd61df31bc377b3403b3d762b1c7a`。签名使用临时验收身份，私钥已清除，未上传。

本轮证据位于 `build/verification/e3-final/distribution-report.json`、`build/verification/starter-e3/verification.json` 与 `build/verification/theme-fix-20261114/`。专项自动检查见 `theme-verified/report.json`，完整视觉复查及跨路像素对照见 `theme-visual.json`。前期探针与未通过的验收尝试保留，未算作通过；包括新增测试的 API 调用错误、隔离消费者样例的异常类导入错误，以及将 OMML 省略的自动黑色误当作缺少颜色属性的验证器错误，均修正后重新验证。

## 验收契约

- 一次显式字体族配置同时提供正文、公式文字和 Word 四脚本槽；PDF 严格使用物理字形与已登记变体，DOCX 普通正文与列表标记保留字体声明而不检查本地物理字形覆盖。阅读端缺少字体的处理由其系统决定；未登记角色仍拒绝，字体来源按一次操作的对象身份复用。
- 全局主题与声明式局部覆盖保持意图；Markdown、HTML、XML 的正文、六级标题、单元格及表格默认值独立传递，显式内容属性／CSS／XML 声明按各自优先级覆盖，不被正文完整值抹平。
- `ContentOptions.Resolved.styles()` 保留不可变语义样式；原 8／9 参数构造器与既有访问器继续可调用。record 组件列表和值相等性的状态增加 `styles`，不承诺反射／解构形状不变。
- 同源 Markdown 和 XML 保留文字、表格、图片及公式出现；PDF-only 不因 OMML 子集失败，严格 DOCX 不悄悄转图。
- 默认缓存生效，关闭、失败、取消和预算仍遵循资源所有权；路径失败保持旧目标，借用流不被关闭。
- 只引入 starter 的独立消费者能通过发布坐标解析完整依赖；BOM／SBOM／POM／Gradle 元数据范围与实际 JAR 一致，不存在同名类的重复提供。
- PDF 检查显式字体嵌入、文字与公式逻辑来源、原图和实际路径；DOCX 检查标准字体槽、原生数学和对象最小行距，随后独立渲染全部页面。

## 可复现入口

从仓库根目录使用 Java 21 和 Gradle Wrapper，Windows 示例：

```powershell
.\gradlew.bat build sbom publishLocalBundle apiCheck :examples:easyExport
powershell -NoProfile -File scripts/verify_local_consumer.ps1
powershell -NoProfile -File scripts/verify_starter_consumer.ps1
python scripts/verify_easy_export.py `
  --consumer-build-directory <successful-consumer-build-directory> `
  --consumer-build-directory <successful-pom-only-consumer-build-directory> `
  --output-directory build/verification/easy-export/output-<new-run>
```

Python 使用项目锁定的验证环境；输出目录必须为新的忽略目录。Linux／macOS 使用 `./gradlew`。通过显式 `--soffice <independent-reader-executable>` 执行 DOCX 阅读器渲染；该工具仅用于验收，不进入生产依赖。未传此参数时只检查 DOCX 标准结构，必须如实登记未执行阅读器验证。

主题专项消费样例位于每个调用方的 `output/easy-export/theme`。使用 `scripts/verify_content_theme.py`，按上述方式重复传入四个成功的 `--consumer-build-directory`，并指定新的 `--output-directory` 与 `--soffice`。样例覆盖 Markdown、HTML、Markdown 中的 HTML、XML、显式请求值、祖先 CSS 和全局黑色；逐项核对六级标题、正文、单元格和公式。PDF 含公式的行可能使用轮廓与 1 pt 非绘制读取锚点，字号证据使用独立纯文字探针及公式真实轮廓控制框，不能把锚点字号当作可见文字字号。DOCX 的自动黑色按实际 OOXML 继承解释，不要求处处写出冗余颜色属性。

本批还执行了 `scripts/verify_signed_distribution.py --version 0.0.0-verification-e2 --native-classifier natives-windows --output build/verification/e2 --gradle-home build/gradle-validation`。单依赖消费通过 `-Version 0.0.0-verification-e2 -RepositoryDirectory build/verification/e2/repository` 使用同一批制品。输出验证器接收完整消费者和单依赖消费者各两种模式的四个目录；不从工作区替换运行类。

公开样例预期提供同源 Markdown／XML 各 5 次公式、1 张表和 1 幅自生成图片，另有 HTML、字面文本及视觉专用公式。消费者不依赖示例私有类，额外核对 starter JAR 和唯一类提供者。脚本使用锁定的 pypdf、PDFium、Pillow 与外部 Poppler，检查全部生成 PDF 页并生成双引擎图片；Markdown／XML 同引擎像素需相同。DOCX 独立打开后再双引擎渲染，完整页视觉复查与自动检查分开记录。

## 2026-11-13 基线结果与边界

| 检查 | 本批结果 |
| --- | --- |
| 全量构建与测试 | `build sbom publishLocalBundle` 及最终签名构建成功；2,675 项 Java 测试，0 失败、0 错误、0 跳过；starter 19 项、单目标数学 7 项、延迟文字字体 5 项 |
| 公共 API | 18 个库、802 个可访问类型；0 不兼容声明、103 项新增、0 第三方类型泄漏；API 检查器自身 6 项回归通过 |
| 分发 | 18 库及 BOM 共 19 个项目组件、92 项签名制品；源码、Javadoc、许可、POM、Gradle 元数据及 SBOM 校验通过，篡改拒绝通过；719 份构建／消费输入前后一致 |
| 完整消费者 | Gradle 元数据和仅 POM 两路分别解析本批全部 18 个库，JAR 摘要与签名仓库逐项一致，生成统一入口样例 |
| 单依赖消费者 | 两路均只声明 starter，一个直接依赖、无 BOM；14 个自研模块、26 个运行制品；10,457 个 Java 21 有效类，0 重复提供者；两路运行制品摘要相同并与本地自研 JAR 核对；未引入 OpenType、native 或 PDF 编辑模块 |
| 无打印运行 | 实际分发 ZIP 的 18 个自研 JAR、34 个运行依赖摘要通过；8 个消费者含统一入口在无桌面且禁止加载打印类的 JVM 中成功，3 个打印类负控制被拒绝 |
| PDF | 示例和四种消费路径共 25 份单页 PDF，全部经 Poppler／PDFium 144 DPI 渲染与独立文字提取；显式字体、原图、公式曲线／ActualText、字框边界检查通过；各组 Markdown／XML 同引擎像素完全一致 |
| DOCX | 共 20 份包通过标准字体槽、表格、图片、原生 OMML、对象最小行距检查；按相同 SHA-256 分为 4 个独立输入，各实际打开并生成 1 页 PDF，再经双引擎渲染；其余文件准确字节相同，报告明确登记渲染复用 |
| 视觉复查 | 五组共 15 张全页联系表全部查看，另放大检查正文、分数、中文公式文字、格内根式、图片及视觉专用公式；未见缺字、裁切或对象覆盖 |
| 配套回归 | 发布流程 9 项、文档准备 7 项、项目规则检查通过；既有 production 独立结构及双渲染回归通过 |

输出工具为 pypdf 6.10.0、PDFium 5.13.0、Pillow 12.3.0、Poppler 26.07.0；DOCX 阅读器为 LibreOfficeDev 27.2.0.0.alpha0。生产导出不调用 LibreOffice。Word 公式以标准 OMML 生成；本批未进行 Microsoft Word 编辑、保存及回读，不扩展为一般 Word 兼容结论。

HTML 混排和视觉专用公式的部分普通文字通过 ActualText 表达；pypdf 普通文字提取不能完整反映这些逻辑文字。验证器分别检查物理文字／ActualText、PDFium 独立提取和最终画面，不删减正文断言。初次签名运行因验收期间输入改变而被正确拒绝；两次早期输出脚本运行因只检查普通文字流而失败，保留证据，均未算作通过。最终分发与输出在新目录重新完整执行。

本地证据分别为 `build/verification/e2/distribution-report.json`、`build/verification/starter-e2/verification.json` 及 `build/verification/easy-export-20261113/output-verified/report.json`。starter 坐标为 `com.mirakyux.export4j:export4j-starter:0.0.0-verification-e2`，本地 JAR 位于 `build/verification/e2/repository/com/mirakyux/export4j/export4j-starter/0.0.0-verification-e2/`，SHA-256 为 `f6a813dc8f4c5784a439c746567990e725e655a3faf1701561beff55c09c7216`。本批分发 ZIP 的 SHA-256 为 `043eda083c660077f6772c79b916004fe7e0bc315b7132952048beed4314ee39`；SBOM 为 `692fe86c6e7a914fdd69485a00d865ae15965a55159425b4f30e41926a3cf6ca`。

正文 XML 以外的页区／分节／注释目前明确拒绝；不承诺任意 HTML、LaTeX、复杂字体脚本或 Word 无损转换。默认无超时和实际输出字节上限，但解析／数学／布局结构预算仍生效；本批不引入新的性能或 RSS 结论。设计与调用约束见[统一入口](../easy-export-api.md)和 [ADR-0118](../adr/0118-configured-export-entry.md)。
