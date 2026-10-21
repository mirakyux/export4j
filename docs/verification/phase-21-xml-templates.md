# 阶段二十一：安全声明式模板验收

- 日期：2026-10-16。
- 范围：GAP-07 的版本 1 离线 XML 子集、既有模板安全边界及隔离调用方。
- 契约：[API](../xml-template-api.md)、[ADR-0025](../adr/0025-safe-declarative-templates.md)。

## 实现与边界

新增 `export4j-adapter-template`，共 15 个项目模块。XmlTemplate 通过 JDK 默认 StAX 解析有界源文本，再编译到既有 DocumentTemplate。支持段落、标题、一级无序列表、分页、矩形语义表格、span、显式行内图片、if、each 和动态 rows；不另行实现绑定、换行或分页。

结构和所有分支的表达式在编译期检查。数据值不递归解释为 XML 或表达式。命名空间、DTD、外部／自定义实体、处理指令和 include 拒绝；图片仅选择调用方注册的不可变资源。源长度、元素数、XML 深度与文本码点预算独立于数据／展开预算。

同批修正 DocumentTemplate 接纳大数子类、追加后才检查文字预算、空循环缺少有界工作计费等问题。Map 键计入文字预算，循环／单元格／行内工作计入访问预算；词法父链避免每次循环复制根 Map。未引用的过大数字也预检，非法或超长表达式不进入诊断正文。

编译错误返回稳定代码和相关元素的一基行列。未知内容不静默丢弃。动态绑定保留安全路径诊断，不提供 XML 行列映射；图片 alt 等展开后的内容仍执行既有语义模型校验。Markdown、HTML/CSS、链接、最终字段、注释、合并网格和模板脚本仍不支持。

## 构建与行为验证

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle `
    :examples:xmlTemplates :examples:capabilities :examples:production `
    :examples:advancedPdf :examples:flowPdf :examples:tablePdf `
    --write-locks --no-daemon --console=plain
& scripts/verify_local_consumer.ps1
~~~

完整构建通过，55 个测试类、378 项测试，失败／错误／跳过均为 0。新增 33 项有意义行为测试，包括模板结构、全部分支表达式、循环作用域、文本注入不执行、图文 UTF-16 来源、两层预算、Reader 最多读取限额加 1 且不关闭、IOException 原样传播、取消恢复、大数子类零重载调用，以及 loopback 服务收到的外部实体／DTD／XInclude 请求为 0。

嵌套 column／cell／span 的非法尺寸报告自身元素行列；显式 ID 全模板去重，自动 ID 避开先后声明的全部显式 ID。无 Java 编译警告；进阶样例的两条缺字占位警告是预期运行诊断。Gradle 对本地 SNAPSHOT 锁的可变制品提示不代表正式版本已冻结。

新增库、source／Javadoc JAR、BOM 和 SBOM 均已本地发布。SBOM 为 13 个自研库和 4 个原有外部运行依赖，共 17 个组件。生产验证器同步增加精确数量及模板模块存在检查，保留制品哈希、许可证、BOM 和调用方集合核对。新模块只依赖模型与 JDK，不新增第三方 Maven 坐标。

隔离调用方读取成功构建目录 `verification/consumer/build/validation-271fcef69542`；运行时解析到新模块的公开 Maven 坐标，生成的模板 PDF 验证 false 分支、each、rows、span 和显式图片。成功指针位于 `build/verification/consumer-build-directory.txt`，以后验收须读取新指针。

## 独立输出验证

~~~powershell
& build/verification/python-phase13/Scripts/python.exe -X utf8 scripts/verify_xml_templates.py
& build/verification/python-phase13/Scripts/python.exe -X utf8 scripts/verify_production.py
& build/verification/python-phase13/Scripts/python.exe -X utf8 scripts/verify_capabilities.py
& build/verification/python-phase13/Scripts/python.exe -X utf8 scripts/verify_advanced_pdf.py
& build/verification/verapdf/verapdf.bat --format xml examples/build/capabilities/archive.pdf
~~~

模板报告输出 PDF、DOCX 和 SVG。PDF 为 420 × 480 pt、28 pt 页边距，共 3 页；五条动态记录含 45 个独立 OBS 原文标记，源顺序完整且不重复，至少一条动态行实际拆至下一页。三页均有完整表头，72 pt 固定列与 292 pt 余量列保持；两张图片复用一个显式 PNG，分别位于首尾页。true 分支、两项 each 及蓝色下划线 span 均保留。

DOCX 使用一个标准表格、六个原始语义行、重复表头属性、允许拆行属性和一份去重媒体，保留全部原文、样式和图片替代文字。没有将 PDF 片段写成伪 Word 行。SVG 检查全部原文顺序和图片。没有外部媒体关系、链接或最终字段。

报告 3 页及隔离调用方 1 页均以 Poppler 和 PDFium 独立渲染并逐页复查。检查原文顺序、页面边界、表格几何、蓝色文字及下划线；交叉渲染仍使用均值误差小于 4、变化像素比例小于 0.06 的既有阈值，不更新视觉基线。

最终 45 条完整 PDF 正文均与独立期望核对。四页均值误差为 1.430652、2.177552、2.039653、1.743420；变化像素比例为 0.006458、0.010356、0.008864、0.020170。OBS01 的原始记录实际分布于第 1、2 页。全部页面复查未见裁切、重叠、丢字或异常留白。

样例最后一次正文更新曾与编译取源时间交错，已统一重新执行 `:examples:build :examples:xmlTemplates`。旧辅助函数仅识别蓝色轮廓，不能证明普通蓝色文字的样式；新验证器按 PDF 的文字颜色及下划线几何独立检查，没有修改库布局或放宽阈值。

既有 production、capabilities 和 advanced 三组独立回归通过。重新生成的归档样例由 veraPDF 验证为 PDF/A-2b，144 规则、10,629 检查通过，失败为 0；报告尺寸及时间戳与最终文件核对。未修改签名路径，本轮未重新生成签名或扩展其合规声明。

## 证据与未验证范围

证据位于 `build/verification/`：`xml-template-tests.json`、`xml-template-final-build.log`、`xml-template-example-final.log`、`xml-template-consumer.log`、`xml-template-production.log`、`xml-template-capabilities.log`、`xml-template-advanced.log`、`xml-template-verapdf.xml`、`xml-template-pdfa.json`，以及 `xml-templates/report.json` 和全部独立渲染页。

字体沿用已登记资源；模板、数据和生成 PNG 为项目自研 Apache-2.0 样例。

本轮 DOCX 进行独立 OOXML 结构／内容检查，没有执行本机 Word 排版与保存回读。仍不保证 Word 与 PDF 同分页、任意 XML／HTML 无损导入、流式布局或通用脚本沙箱。调用方提供的 Reader／容器是可信代码。
