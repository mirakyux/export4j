# 阶段二十四验收：单向长表与逐页 PDF

日期：2026-10-16。对应 GAP-09，[设计](../adr/0031-single-pass-table-output.md)与[使用指南](../streaming-table-api.md)。本批不新增模块、第三方依赖或字体；继续 16 模块及现有包名／坐标。

## 实现范围

StreamingTable 仅保存固定 point 列宽、页设置、内边距、元数据和有限表头；正文由 TableRowSource.Factory 在导出时同步打开。StreamingTableLayouter 每行测量一次，保留表头、当前页和最多一个待放置行；空源恰好一页，超高首行在表头空页交付前失败。源行／格／码点分别计费，重复表头占输出行／诊断预算，保留物理页、源行列和格内 UTF-16。

DocumentPainter.paintPage 独立绘制单页 IR。PageBackend／PageWriter 同步接收并释放 DisplayPage，显式 finish，close 仅清理且幂等。PdfBackend 与完整 write 共用累计预检计数和既有渲染；逐页转成 PDFBox 对象，仍保留 PDDocument 页树／COS 对象，不是恒定内存 PDF 编码器。

DocumentEngine 提供 export(spec, factory).backend(...).limits(...).diagnostics(...).options(...).to(...)，返回 StreamingExportSummary 扁平计数。开始时快照请求配置，重入修改只影响下次导出。先开后端再开源，源关闭成功后才 finish，写出器关闭失败也使导出失败。借用目标流不关闭；路径以同目录临时文件原子替换，关闭／输出／取消失败均保持旧目标，清理错误附加为 suppressed。Progress.total=-1 表示未知总量。

## 构建与行为验证

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle `
    :examples:streamingTable :examples:writeStreamingTableClasspath `
    :examples:propertyStyles :examples:textCorpus :examples:markdownTemplates `
    :examples:pdfOperations :examples:xmlReferences :examples:textPdf :examples:flowPdf `
    :examples:tablePdf :examples:componentPdf :examples:advancedPdf :examples:capabilities `
    :examples:production :examples:authoring :examples:inlineImages :examples:continuousColumns `
    :examples:mixedColumns :examples:inlineFields :examples:docxFields :examples:xmlTemplates `
    :examples:semanticTables :examples:semanticTableFlow :examples:gridSemanticFlow `
    :examples:styleDefaults :examples:gridContinuation :examples:multiFloats `
    :examples:staticForms :examples:adrForm --no-daemon --console=plain
& scripts/verify_local_consumer.ps1
~~~

全量构建成功，用时 2 分 3 秒；65 个测试类、552 项测试，失败／错误／跳过均为 0。新增 62 项：模型 5、布局 27、引擎 10、PDF 会话 20；覆盖惰性／最多一行预读、来源和重复表头、多行／空源／完整末页、全部预算、严格损失拒绝、字体诊断、取消／超时／上下文恢复、关闭顺序／suppressed／目标保护、请求快照、累计 PDF 预算和旧输出几何／像素一致性。

首次四模块定向测试通过后，交叉复核发现 66.6 pt 正文高、22.2 pt 行高的二进制舍入可能误续页。三处适配检查统一采用既有一 ULP 容差，增加临界表头／正文及真实超高拒绝回归。随后全量样例因错误拒绝正常 FONT_FALLBACK／INFO 中断；只修样例与独立期望，保留字体顺序并精确核对 770 项信息诊断，任何 WARNING 仍失败。生产实现未为此降低检查。失败日志保留，最终全量复验成功。

Java 生产／测试／调用方以 -Xlint:all -Werror 编译，Javadoc、源码包、BOM／SBOM 和本地坐标通过。新隔离 Maven 调用方目录为 verification/consumer/build/validation-54945662b462，编译／运行／解析库存成功；运行集合仍为 14 自研库和 4 既有外部制品，摘要与 BOM 由生产独立验证器核对。后续批次必须读取最新成功指针。

## 独立 PDF 与历史回归

~~~powershell
& build/verification/python-phase13/Scripts/python.exe -X utf8 scripts/verify_streaming_table.py `
    --consumer-build-directory verification/consumer/build/validation-54945662b462
~~~

固定 pypdf 6.10.0、pypdfium2 5.13.0、Pillow 12.3.0 和 Poppler。独立几何／源期望定义在 Python 中，不从 Java 清单读取期望坐标。

| 输出 | 页数 | 实际检查 |
| --- | --- | --- |
| streaming | 4 | 55 行唯一且有序，分配 14／14／14／13；多行正文整行移动，逐页一个完整表头，177 格边框及全部字符格内几何；168 源格／2,258 码点／184 输出行／483 命令／770 信息诊断／18,724 字节 |
| empty-header | 1 | 表头独占且无虚构正文；EOF 一次、源关闭一次 |
| empty-plain | 1 | 恰好一张空页，无虚构表头／格边框；EOF 一次、源关闭一次 |
| consumer | 2 | 15 行分配 8／7，逐页表头，34 格边框；公共坐标的源关闭和输出计数 |

共 8 页双引擎渲染，全部 192 DPI Poppler 完整页逐张查看；另复查末页。未见裁切、重叠、缺字、拆行或额外空白页。PDFium 全部非空字符在其格内；最大均值误差 3.308792 < 4，变化像素比例 0.031299 < 0.06。未降低阈值或刷新基线。

23 组历史独立回归通过：advanced_pdf、production、capabilities、authoring、inline_images、multi_floats、continuous_columns、mixed_columns、inline_fields、docx_fields、static_forms、adr_form、grid_continuation、semantic_tables、semantic_table_flow、grid_semantic_flow、style_defaults、xml_templates、text_corpus、markdown_templates、pdf_operations、xml_references、property_styles。最新归档 PDF 经 veraPDF 验收 PDF/A-2b，144 规则／10,629 检查，零失败。

## 独立 JVM 容量

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/measure_streaming_table.ps1 `
    -SkipBuild -IncludeSmallHeapControl
~~~

新运行 `7072bc1edcc04fd0a48a87476e290584` 的 7 个独立 JVM 全部成功。布局丢弃页和真实 PDF 各运行 1,000／10,000／100,000 行，堆上限 512 MiB；另运行 64 MiB 堆的 100,000 行布局控制组。测量期间未并发执行 Gradle 或历史渲染任务，每组只运行一次。

环境为 Windows 10.0.26300、Intel Core Ultra 5 125H、18 个逻辑处理器、33,832,394,752 字节物理内存、Java 21.0.10+7-LTS 和 G1。字体仅为已登记的 LiberationSans-Regular.ttf，410,712 字节，SHA-256 为 `76d04c18ea243f426b7de1f3ad208e927008f961dc5945e5aad352d0dfde8ee8`；运行时资源与源字体摘要相同。数据源逐行生成固定长度 ASCII，三列固定宽，600 × 840 pt 页面、20 pt 页边距、2 pt 格内边距、8 pt 字号和 10 pt 行高。

下表内存和临时盘均以 MiB 计。观察堆峰是 Java 检查点采样到的最大已用堆；工作集、私有字节、句柄及临时盘是外部约 100 ms 间隔观测值，不是精确生命周期最大值。

| 模式 | 正文行 | 堆上限 | 页数 | 秒 | 行／秒 | 观察堆峰 | 工作集峰 | 私有字节峰 | 句柄峰 | 临时盘峰 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 布局丢弃页 | 1,000 | 512 | 18 | 0.577 | 1,732.342 | 20.000 | 118.875 | 207.992 | 397 | 0 |
| 布局丢弃页 | 10,000 | 512 | 179 | 1.099 | 9,096.695 | 39.574 | 159.145 | 232.316 | 397 | 0 |
| 布局丢弃页 | 100,000 | 512 | 1,786 | 3.023 | 33,075.465 | 47.547 | 152.531 | 226.281 | 397 | 0 |
| PDF TEMP_FILE | 1,000 | 512 | 18 | 6.031 | 165.805 | 56.750 | 257.258 | 329.098 | 444 | 0.125 |
| PDF TEMP_FILE | 10,000 | 512 | 179 | 41.928 | 238.504 | 61.119 | 210.297 | 280.539 | 444 | 0.750 |
| PDF TEMP_FILE | 100,000 | 512 | 1,786 | 325.281 | 307.427 | 81.915 | 248.508 | 321.312 | 444 | 7.062 |
| 布局丢弃页控制组 | 100,000 | 64 | 1,786 | 2.014 | 49,659.359 | 39.941 | 167.230 | 241.133 | 397 | 0 |

Java main 内计时包含字体初始化、行生成／布局、PDF finish／保存和原子移动；排除 main 前 JVM 启动／类初始化、外部启动／采样及最终报告写入。进程采样从启动持续到观测退出，包含启动和关闭。临时盘范围是隔离 java.io.tmpdir 与活动原子输出临时文件，排除最终 PDF。三份最终 PDF 分别为 78,232／687,680／6,788,471 字节。

各组新运行 ID／独立 PID、源调用 `rows + 1` 次（包含一次 EOF）、关闭一次、源格 `3 × (rows + 1)`、源码点 `68 × rows + 22`、输出行 `3 × (rows + pages)` 及 `ceil(rows / 56)` 页全部通过。pypdf 独立核对容量 PDF 的全部页数及首末页完整行序／表头；容量文件没有全部渲染或人工逐页查看，不能复用小样的全页检查表述。

64 MiB 控制组证明这个固定输入的 100,000 行布局可以完成，不限制进程总内存。PDF 观察堆随规模从 56.750 增至 81.915 MiB，PDFBox 仍保留页树／COS 对象。工作集和私有字节峰值不单调，包含运行时／本机分配；启动、JIT、GC 与单次运行差异也影响吞吐。没有强制 GC、内存池峰值求和或恒内存／长期无泄漏结论。

首次采样脚本运行中，1,000 行 Java 布局成功，但 Windows PowerShell 5.1 的 Process 在 Refresh／HasExited 后返回空退出码，脚本未发布成功指针。独立 java -version 探测确认缓存原生 Handle 后退出码可读取；只修采样脚本，保留失败运行与日志，再从新 ID 重跑全部 7 组。

7 组退出后的临时文件数和字节数均为 0，递归检查隔离 tmp 及输出目录的 `.export4j-*`，包含空临时文件。文件计数要求在本轮执行中补充，因此本轮在所有 JVM 结束后独立审计并补入摘要；审计前 JSON 原件保留。脚本后续直接记录 remainingTemporaryFiles，非 0 时拒绝成功指针。这证明本批退出清理，不证明长期服务没有泄漏。

容量证据：完整结果与退出审计为 `build/verification/streaming-table/capacity-7072bc1edcc04fd0a48a87476e290584/report.json` 和 `post-run-audit.json`；测量说明为 `build/verification/streaming-table/capacity-findings.md`；运行日志、独立审计日志与首次脚本失败日志分别为 `build/verification/phase24-capacity.log`、`phase24-capacity-audit.log` 和 `phase24-capacity-initial-wrapper-failure.log`。每组 process.csv、process-report.json 和 Java report.json 保留在对应运行子目录。

## 限制与复评

仅固定列、普通文字和整行分页；富文本／图像／字段、自动宽、合并／嵌套／拆行及配置页模板／导航／注释／字体角色明确拒绝。未来引用、总页数、脚注、平衡、DOCX、PDF 加密／归档／签名未接入单向路径。现有完整文档与安全选项保持，不扩大为任意大文档恒定内存或长期无泄漏。

本批固定列普通文字的单向源、逐页编排及容量子目标已验收，GAP-09 整体登记为部分支持。下一项 GAP-12 的源码评估已定位：压缩的源 cell 列表下标和物理网格列号必须分开，columnSpan 须同时贯通模型、测量／拆行游标、字段／外链、绑定／XML 与标准 DOCX gridSpan。rowSpan 与递归格内容各需独立契约，不能只放行 OOXML 标签。具体执行范围以更新后的[剩余计划](../remaining-capabilities-plan.md)为准。

证据：build/verification/phase24-final-build.log、phase24-tests.json、phase24-consumer.log、phase24-streaming-table.log、phase24-各历史验证器.log、phase24-verapdf.xml，以及 streaming-table/pdf-checks/report.json／manual-review.md 和全部渲染页；容量记录见上文。最初 phase24-build.log 是失败记录，不作为最终构建成功证据。

本批仅本地构建与隔离发布，没有提交、推送、创建 remote、远程 CI 或正式发布。
