# 阶段十四验收：公共链式构建

日期：2026-10-15。范围是公共编写与配置层，未将内部结果和 SPI 改成 Builder，未添加依赖。

## 交付

- 语义文档、表格、单元格、分节、模板和格式的具名构建入口。
- 组件层 `Documents.Builder`，输出原有 `Document`；支持可见的列、行、表和格嵌套。
- 字体组、页面／页区、文字、图片、图形、分栏、浮动、页码、交互集合与字段的具名设置。
- `DocumentEngine` 默认页模板／导航／注释配置及链式导出，复用原有原子路径与同步执行控制。
- PDF／Raster／DOCX 配置、预算、加密与权限构建入口；原构造器和工厂保留。
- `GridTable` 推导跨行格后的位置、默认格设置、最小行高，以及与声明顺序无关的行高约束计算。

契约见[链式 API](../fluent-api.md)与 [ADR-0018](../adr/0018-fluent-authoring.md)。开发规约与根 `AGENTS.md` 已同步。

## 行为验证

全仓当前共 254 个 JUnit 测试，0 失败、0 错误。新增 11 个测试覆盖：

- 跨行／跨列自动定位，默认值覆盖，保留回调 Builder 后继续修改不影响父快照。
- 空槽、越界、跨度重叠、跨越表头边界、非法尺寸明确拒绝。
- 同一格集合反向声明后，总高度与每个最终单元格矩形相同。
- 嵌套流的来源顺序、文字最小高度、语义表格列宽推导、混合字段来源。
- 默认页脚先预留正文区域，最终页码解析；引擎快照不随原 Builder 改变。
- 表单文本／选项／单选／复选初始值在最终交互数据中保留，选项列表独立快照。
- 原子输出失败保留旧文件并删除临时文件，输出字节预算返回 `OUTPUT_LIMIT`，借用流不关闭。
- 字号／显式行距的优先级、样式副本、自动块 ID 的同一命名空间与分节快照。
- 嵌套模板分支的快照、条件／循环绑定及缺值明确失败。

Java 编译使用既有 `-Xlint:all -Werror`，全模块 Javadoc 与打包通过。新增 Builder 没有引入反射、Lombok 或第三方公共类型。

## ADR 表单

同一虚构数据样例已重写，使用按行声明的合并格和最小行高，移除了位置格工厂与最小高度文字模拟。

| 检查 | 当前结果 |
| --- | --- |
| 紧凑样例 | 3 页，114 格，16 个选中标记 |
| 描述增加为 12 倍 | 4 页，描述出现 12 次 |
| 描述增加为 100 倍 | `COMPONENT_LAYOUT_FAILED` |
| 表头声明顺序探测 | 两种顺序均为 98 pt；首次为 98／111 pt |
| 最终页脚 | 分别为 1～3、1～4 |
| 独立几何检查 | 格数、页归属、边界、无面积重叠、文字及勾选路径全部通过 |
| 独立渲染 | Poppler／PDFium 全部 7 页通过；最大平均通道误差 3.619539，最大变化比例 0.029663 |

192 DPI、Gaussian blur 2 px，对应原有 0.75 pt 比较半径。沿用 4／0.06 的密集生产容差，未刷新历史视觉基线。全部页面已查看，重点复查密集表头、跨页描述和页脚。

主样例 226 行、216 非空行；独立的静态勾选辅助 49 行、47 非空行。合计 275／263，首次为 283／265。代码总量下降有限，主要收益是行列结构可读、具名参数和通用布局能力；没有把辅助文件移出统计来宣称大幅减码。勾选辅助仍仅适用于本例普通水平文字，不是通用富文本或可编辑控件。

## 命令与证据

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:adrForm :examples:componentPdf :examples:advancedPdf :examples:tablePdf :examples:mixedColumns :examples:production :examples:flowPdf --no-daemon --console=plain
& scripts/verify_local_consumer.ps1
& build/verification/python-phase13/Scripts/python.exe scripts/verify_adr_form.py
& build/verification/python-phase13/Scripts/python.exe scripts/verify_advanced_pdf.py
& build/verification/python-phase13/Scripts/python.exe scripts/verify_mixed_columns.py
& build/verification/python-phase13/Scripts/python.exe scripts/verify_production.py
.\gradlew.bat -g build/gradle-validation :examples:capabilities :examples:inlineImages :examples:textPdf --no-daemon --console=plain
& build/verification/python-phase13/Scripts/python.exe scripts/verify_capabilities.py
& build/verification/python-phase13/Scripts/python.exe scripts/verify_inline_images.py
.\gradlew.bat -g build/gradle-validation :examples:capabilities -PsignerOpenSsl=openssl -PsignerCertificate=build/verification/signing/dev-cert.pem -PsignerKey=build/verification/signing/dev-key.pem --no-daemon --console=plain
& .\build\verification\verapdf\verapdf.bat --format xml --flavour 2b examples/build/capabilities/archive.pdf > build/verification/fluent-verapdf.xml
& build/verification/python-phase13/Scripts/python.exe scripts/verify_capabilities.py --openssl openssl --verapdf-report build/verification/fluent-verapdf.xml
~~~

隔离 Maven 调用方已通过，新增 `consumer-fluent.pdf`，只通过本地发布坐标验证 DSL、合并格、默认页脚、PDF 临时缓存、执行预算和路径输出。成功输出目录通过 `build/verification/consumer-build-directory.txt` 记录。

进阶、混合栏、生产、能力与行内图片的独立检查均通过。能力检查核对 AES-256 权限、错误密码拒绝及归档样例渲染。重新生成签名样例并通过 OpenSSL 验签、篡改拒绝与渲染对照；本轮归档文件通过 veraPDF PDF/A-2b 验证，144 条规则、10,629 项检查通过，0 失败。配置仍使用忽略目录下的开发证书与私钥，未引入凭证资源。生产检查新增隔离调用方链式样例的最终内容顺序、最终页脚及两渲染器对照。

构建日志为 `build/verification/fluent-build-final.log`，隔离调用方日志为 `fluent-consumer.log`。独立检查日志采用 `fluent-*-check.log`，ADR 结构与渲染数据在 `build/verification/adr-form/verification.json`。

安全样例构建日志为 `fluent-security-build.log`，当前归档合规报告为 `fluent-verapdf.xml`，签名与归档独立摘要在 `build/verification/capabilities/report.json`。

## 兼容性与限制

保留已有构造签名、短工厂和 `write` 入口。新增 record 分量会改变反射分量和字符串表示；行高修复可能改变原先过量分配的布局与分页。未发布项目不承诺预览版本的完整二进制兼容性。

合并组件格内部续页、通用语义勾选控件与一般 Word／PDF 无损互转没有在本次实现。可编辑表单已有独立回归，本次没有新增真实阅读器人工填写证据。没有提交、推送或正式发布；只向仓库构建目录进行隔离本地发布。
