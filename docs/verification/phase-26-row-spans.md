# 阶段二十六：语义跨行合并与 DOCX vMerge

日期：2026-10-16。对应 GAP-12 的跨行子目标，设计见 [ADR-0033](../adr/0033-semantic-row-spans.md)，使用见[跨行合并 API](../row-spans-api.md)。本批仍为 16 模块，无新增依赖、字体或外部资源，不改变包名／制品坐标。

## 实现与兼容边界

实现二维真实锚点、全覆盖行、确定行高及连通行组原子分页，贯通最终字段、图文链接、Java／XML 静态绑定和标准 DOCX vMerge。TableCell.rowSpan 正整数、默认 1，保留旧 2—6 参数构造器和 Builder／toBuilder 内容；record 分量由 6 变为 7，解构／反射及已编译调用方需注意迁移，不宣称完全二进制兼容。TableRow 只保存新锚点，coveredRow／coveredHeaderRow 表示全覆盖行，合法性由完整 Table 检查；本行 columnCount／cellAtColumn 不能解析前行占用，二维位置使用 TableGrid。

网格按 O(列数) 活动覆盖及 O(锚点数) 稀疏视图准备，不创建二维槽矩阵。ownerAt 最坏扫描历史行／锚点，anchorAt 在指定行二分；布局和外链操作内复用一次网格。固定／比例格宽只扣一次 padding，单行约束先建立基础高度，无此类锚点的行基础为 0；纵向约束按跨度／原始行／物理列排序，正缺额等分、末行补舍入差。

跨度跨越边界组成传递连通组，正文、区域 Cursor、ContinuousColumns 及网格 SemanticTable 整组移动；短区空取不消费，超高组失败。全覆盖行不造假 IR 内容，但高度计入真实矩形。源只准备／计费一次，结构预算仍计 rows×columns，重复表头逐次计输出预算。字段、图片／文字外链及诊断保留原始物理列和局部 UTF-16。

DOCX 使用标准 restart／continue 与 gridSpan，覆盖格只在序列化生成空 tc，导入归一为唯一锚点。孤立续接、完整区间分拆／拼接／不匹配、错误父链、重复属性、非法值、越界、跨表头和非空 continuation，两种损失策略均拒绝。空续接格仍检查格式：未知样式 STRICT 拒绝、ALLOW_LOSS 返回诊断。

合并覆盖 Auto、rowSpan 与 allowRowSplit、动态单行原型纵向跨度、单向 StreamingTable 合并及递归格内容仍拒绝；旧无纵向合并的完整行拆分保持。

## 构建与行为验证

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle `
    :examples:rowSpans :examples:columnSpans :examples:streamingTable :examples:propertyStyles `
    :examples:textCorpus :examples:markdownTemplates :examples:pdfOperations :examples:xmlReferences `
    :examples:textPdf :examples:flowPdf :examples:tablePdf :examples:componentPdf `
    :examples:advancedPdf :examples:capabilities :examples:production :examples:authoring `
    :examples:inlineImages :examples:continuousColumns :examples:mixedColumns :examples:inlineFields `
    :examples:docxFields :examples:xmlTemplates :examples:semanticTables :examples:semanticTableFlow `
    :examples:gridSemanticFlow :examples:styleDefaults :examples:gridContinuation `
    :examples:multiFloats :examples:staticForms :examples:adrForm --no-daemon --console=plain
& scripts/verify_local_consumer.ps1
~~~

全量成功：2 分 17 秒，201 项任务、83 项执行。75 个测试类、**641 项测试**，失败／错误／跳过均为 0。新增 49 项：模型 10、Java 模板 3、XML 5、核心 14、组件 2、DOCX 15。覆盖二维 owner、incoming 空槽、零基础行、排序约束、传递连通组、短区纯跳过、keep-with-next、字段／图文链接、预算、取消及拒绝范围。2048 行全覆盖 DOCX 用执行检查点验证活动区间输出、取消与上下文恢复。

Java 生产／测试／调用方使用 -Xlint:all -Werror，源码包和 Javadoc 通过。末次只删除样例两个未使用导入，随后 `:examples:compileJava` 成功（14 秒、15 项任务、1 项执行），未改变行为。

新隔离调用方为 `verification/consumer/build/validation-1d873e5aea56`；本地 bundle 发布 13 秒，调用方编译／运行／依赖库存 28 秒通过。覆盖双跨度／covered 行、owner／anchor、最终字段／图文链接和标准 DOCX 往返，生产验证使用本次目录，没有复用阶段二十五输出。

## 定向验证与复核

首轮五模块定向测试发现三类问题：新模板测试误用不支持的动态 URL；新连通组样例错误假设无单行锚点的中间行自动占一个行高；新增高度溢出分类改变既有 CONTENT_TOO_TALL。测试改用合法静态 URL，并用真实两行内容建立 30 pt 约束；布局在非有限格高度入口保持既有失败分类，不放宽拒绝规则。首次日志 `build/verification/phase26-targeted.log` 保留。

跨复核同时发现准备网格的稠密空行引用以及 DOCX writer 的 ownerAt 逆向反查会在长全覆盖表退化；分别改为稀疏不可变行视图和活动 TreeMap 区间输出。独立 JDK 探针发现 continuation 格未知 pStyle 静默损失，修复并补回归后，STRICT 拒绝、ALLOW_LOSS 带诊断，错位 tc 容器两策略均拒绝；原失败和修复日志保留。

新 keep-with-next fixture 显式把前后间距设为 0，保留精确断言，没有改分页契约。样例 Java／XML 最初对普通格显式运行的表示不同，统一后又遇到既有 DOCX 默认运行归一化；最终三个绑定格统一声明实际下划线，Java／XML 和原包 DOCX 保留严格模型相等，并独立检查标准 w:u，没有放宽生产归一化。

## 独立输出与历史回归

~~~powershell
& build/verification/python-phase13/Scripts/python.exe -X utf8 scripts/verify_row_spans.py `
    --consumer-build-directory verification/consumer/build/validation-1d873e5aea56
& build/verification/verapdf/verapdf.bat --format xml --flavour 2b `
    examples/build/capabilities/archive.pdf > build/verification/phase26-verapdf.xml
& build/verification/python-phase13/Scripts/python.exe -X utf8 `
    build/verification/run_phase26_regressions.py
~~~

运行前将固定 Poppler 路径加入当前进程 PATH。工具为锁定的 pypdf 6.10.0、pypdfium2 5.13.0／PDFium 153.0.7999.0、Pillow 12.3.0、python-docx 1.2.0 和 Poppler 26.07.0。

| 新输出 | 页数 | 独立检查 |
| --- | ---: | --- |
| groups | 3 | 双行合并表头、incoming 中间空位、全覆盖行、交错双跨度、整组移页、逐页字段与图文外链 |
| columns | 2 | 外网格／ContinuousColumns 短首区、逐栏表头、完整组分配及续页完整栏高 |
| templates | 1 | Java／XML 静态绑定、覆盖行、三个下划线格、最后真实锚点的字段与图片 |
| 新隔离调用方 | 1 | 五个固定合并边框、两个全覆盖行、页码、唯一图像及两条外链 |

**7 页**全部独立解析、Poppler／PDFium 192 DPI 双渲染并逐张查看。固定几何、来源 UTF-16、独立 owner 网格、原子组分页、字段、边框、图文链接及 SVG 顺序均通过。最大通道均值差 1.679687、变化像素比例 0.018666，阈值保持 4／0.06，没有创建或刷新基线。图像按共享段落 ascent 容纳，邻格不承诺自动基线一致。

标准 OOXML 和 python-docx 独立重建 spans.docx 及移除全部 Export4j 私有属性的 spans-standard.docx：6 物理行、5 真实锚点、3 continuation、2 原生页码字段、2 外链、1 嵌入图像；续接格无重复正文，三个富文本格有标准下划线。引擎另以显式字段宽度严格导入标准输入。

**25 组历史独立验收**全部通过，包括进阶、生产、能力、编写、行内图像、多浮动、连续／混合栏、行内／DOCX 字段、静态表单、ADR、网格续页、三类语义表格、默认／属性样式、XML／Markdown、文字语料、PDF 处理、XML 引用、单向长表及列合并。veraPDF 本次验证 PDF/A-2b：144 规则、10,629 检查、失败 0。本批未重新运行外部 OpenSSL 验签或阶段二十四 7 组容量测试；未改变对应后端／容量实现，旧证据不标成本次重跑。

## Word 的实际证据与限制

一次有界私有 Word 16.0 探针读取标准输入为 1 页，5 个真实格的正文顺序正确，PAGE／NUMPAGES 更新均为 1，两次 Update 返回 0；文本外链另暴露为 HYPERLINK 字段。源 SHA-256 前后为 b2cdf3c23e1bf5c205e8b5cb65c65b12d30c93e32cc970001090697d26f9b3fc，没有修改用户文档或生成输入。

探针整体状态保持 **WORD_PROBE_FAILED**：Word 对象模型折叠两个全覆盖物理行，Rows.Count 为 4，先前 6 行／总共 2 字段的断言不成立；随后 Close(0) 因 COM 引用参数要求失败。可用原始数据经独立断言登记为原生加载／字段通过，不改原状态，不放宽 OOXML 物理行断言。自建 WINWORD 49300 已按 PID、精确启动时间及 run ID 匹配清理并确认退出。

run ID 为 aa5bdb7e8a9f4b309807657711793663；保留 word-readonly-probe／status、word-native-evidence.json 与原日志。**本次未调用 SaveAs 或 PDF Export，也没有重跑 Word**。阶段二十五的保存／导出阻塞仍开放，不能宣称本批保存回读、Word PDF 渲染或一般无损互转通过。

## 下一入口

纵向组拆页、递归格内容、Auto 合并、单向源合并、动态多行跨度和一般 Word 兼容仍独立。下一单元为[块格与原子内表设计](../adr/0034-atomic-block-cells.md)：一格一段的模型、IR、模板、全树 ID／来源、共享预算、最终字段／链接及 DOCX 书签／尾段必须一起扩展，不能先放宽标签接受范围。GAP-03／GAP-12 保持部分支持。

## 证据索引

- 构建／测试：build/verification/phase26-full-build-accepted.log、phase26-tests.json、phase26-consumer.log、phase26-final-compile.log。
- 定向修复：phase26-targeted.log、phase26-targeted-retry.log、phase26-full-build.log、phase26-full-build-final.log、phase26-rowspans-final.log；DOCX 复核在 phase26-docx-cross-review/，含原失败与 repaired-probe.log。
- 新输出：build/verification/row-spans/report.json、manual-review.md、七页双渲染 PNG、word-native-evidence.json 及原始 Word 状态。
- 历史／合规：build/verification/phase26-regressions.json、phase26-regressions-run.log、各 phase26-*.log 及 phase26-verapdf.xml。
- 文档／源码审计：build/verification/phase26-source-audit.json，105 份 Markdown、826 个本地链接、41 个相关源码／脚本检查无问题；phase26-powershell-audit.json 中 3 个 PowerShell 脚本解析无错误。下一源码评估原始报告为 phase26-next-nested-cells-assessment.md。

本批仅本地构建、研究及隔离发布验证，没有提交、推送、远程 CI 或正式发布。
