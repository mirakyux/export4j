# 源内容块与继承配置验收

对应[源内容块 API](../content-blocks-api.md)和 [ADR-0115](../adr/0115-source-content-options.md)。本批属于当前开发版，不包含在已发布 RC3 中。

当前状态：**本地构建、输出及隔离分发验收通过（2026-11-13）**。使用工作区实际源码完成验证，保留未提交状态；没有远程发布或部署。下文区分测试、独立输出、视觉检查和签名分发证据。

## 复现入口

使用 Java 21、仓库 Wrapper、固定 Python 验证环境和 Poppler，从仓库根目录运行。先执行相关模块与既有兼容行为检查：

```powershell
.\gradlew.bat :export4j-model:test --tests '*ContentOptionsTest' --tests '*DocumentTemplateSourceTest'
.\gradlew.bat :export4j-adapter-web:test --tests '*ContentImporterTest'
.\gradlew.bat :export4j-adapter-template:test --tests '*XmlContentTest'
.\gradlew.bat build sbom publishLocalBundle apiCheck :examples:contentBlocks
```

示例与独立调用方入口分别为 `:examples:contentBlocks` 和隔离项目的 `contentBlocks`。本批签名验证使用独立本地坐标和新目录，随后分别检查实际示例与制品消费输出：

```powershell
python scripts/verify_signed_distribution.py --version 0.0.0-verification-content-blocks-20261113 --native-classifier natives-windows --output build/verification/blocks-signed
python scripts/verify_content_blocks.py --example-directory examples/build/output/content-blocks --output-directory build/verification/content-blocks-example-output
python scripts/verify_content_blocks.py --consumer-build-directory build/verification/blocks-signed/consumer-project/verification/consumer/build --output-directory build/verification/content-blocks-consumer-output
python scripts/verify_content_blocks.py --consumer-build-directory build/verification/blocks-signed/pom-only/consumer-project/verification/consumer/build --output-directory build/verification/content-blocks-pom-output
```

输出目录必须用于本次新运行，不覆盖旧证据；临时验证身份只用于本地验收，不上传或代表正式发布。本次较长签名目录曾触发 Windows 路径长度限制，最终使用上面的短目录从头构建和验签；失败目录未用于成功结论。

## 行为契约

| 范围 | 必须核对的行为 |
| --- | --- |
| 逐属性声明 | 字体、颜色、字号／行高、缩进／间距、对齐／禁则、旋转及分页分别继承；黑色、空角色、零值及 `false` 明确覆盖；单项恢复不改其他声明 |
| 快照与作用域 | 全局、根、嵌套作用域、单块的优先级确定；兄弟不泄漏，Builder／主题后续修改不改旧值，完整格式与局部样式按原契约处理 |
| 格式与开关 | 默认 TEXT；三种语法明确选择，公式／表格独立开关；关闭时保留原始片段和转义／换行，表格关闭不渲染其内公式；未装配公式处理器明确失败 |
| 公式与保护位置 | 正文及表格公式使用实际字号／颜色；Markdown 代码、链接、图片、引用定义及原始 HTML 受保护，HTML 属性／链接文字不解析；非支持语法不静默回退 |
| 字段绑定 | 静态／CDATA 不插值，整字段源只取一次字符串，缺失／错误类型失败；产物的文字、链接及公式阅读文字不再次解释 `${...}` |
| 动态正文格 | 源、条件／循环、命名空间、跨度和空结果保持；重复表头块式源、同格 `style-if` 混用、非法容器及超深树明确拒绝 |
| 累计预算 | 同一 render 跨循环／分节／格共享 parser session；输入、AST／工作量、表格、公式／矢量预算及模板剩余额度均受约束，不能用多个小源块绕过 |
| 关闭与取消 | 成功／失败／取消均关闭 session，保留主异常及清理失败；同步取消不会降级为文字或部分成功；无网络／文件隐式资源查找 |
| 最终语义 | 不残留 Source，输出 ID 限定命名空间且不碰撞；Caption／CrossReference 外观贯通最终编号，旧构造器及既有模型不重解析 |

## 输出与分发契约

必须从实际 JAR／POM／BOM／SBOM 验证完整依赖与公开 API，在普通元数据和仅 POM 两条隔离消费路径核对解析到的项目 JAR 摘要。示例和制品消费都检查原始文字、来源与块顺序、格／公式位置、字体／颜色、最终字段和页面几何。所有新增 PDF 页由 Poppler、PDFium 独立完整渲染，并登记实际视觉复查范围；其他格式分别核对明确支持的语义或损失。

纯 TEXT、旧 `p`／`span`、公式／表格关闭等控制组应保留其承诺的原文及几何。不能因复用同一排版／绘制实现读回成功，就代替独立结构／渲染检查。不得自动刷新视觉基线、放宽来源断言或把生成式页面数写成人工检查结果。

## 本次结果

| 检查 | 实际结果 |
| --- | --- |
| 完整构建 | `build sbom publishLocalBundle apiCheck` 及源内容、文字、分页、表格、组件、能力、生产、进阶样例通过；成功构建 232 个任务，89 个执行、143 个复用 |
| JUnit | 当前 XML 共 2,573 项，失败／错误／跳过均为 0；包括 Gradle 增量复用结果，不称全部重新执行 |
| 公共 API | 17 个库、776 个可访问类型；固定 RC2 基线未改，0 项不兼容声明、68 项新增声明、0 个第三方 API 类型 |
| 脚本与规约 | 46 项 Python 脚本测试、7 项站点发布测试及项目规约检查通过 |
| 模型／源安全 | Java／XML 模型真实相等；四处 `${secret}` 原样保留，秘密值不出现；作用域、显式 CSS、兄弟恢复、循环格及会话预算／关闭回归通过 |
| 独立 PDF | 三条路径各生成 Java／XML 两份 2 页 PDF；每个渲染器完整渲染 12 页，检查四个公式阅读行及实际路径、原文字面控制、嵌入字体／颜色、图片绘制、两条链接和选区坐标 |
| 像素与视觉 | 每条路径 Java／XML 同引擎像素和字符坐标相同；三条路径全部渲染页也与示例参考逐像素一致。Poppler／PDFium 各两张完整参考页已查看，未见截断、重叠或公式缺失；相同像素页按该参考检查 |
| DOCX | 独立 OOXML 检查两张表、一张图片、两条链接、颜色、字号及固定行高；四项矢量转逻辑文字损失逐项检查，预检与实际报告相同，没有冒充 OMML |
| 负控制 | 删除实际图片绘制但保留资源、损坏关闭表格的原始管道文字，均被验证器按预期拒绝 |
| 普通本地消费 | `verify_local_consumer.ps1` 的元数据和仅 POM 两条路径均通过，新内容、数学及字体缓存入口实际执行 |
| 签名隔离分发 | 18 个组件、87 个签名制品；BOM／SBOM 对齐、篡改拒绝、临时私钥清理和两条隔离消费均通过，各自核对 17 个项目 JAR 摘要 |
| 运行身份 | 实际分发运行包 11,514 个类，没有重复类；`GlyphTable` 仅由官方 `fontbox-3.0.8.jar` 提供 |
| 无打印驱动 | 拒绝加载 JDK 打印类时，六个真实制品调用方均完成输出；三类打印 API 负控制真实失败 |

最终本地坐标统一使用 `com.mirakyux.export4j:*:0.0.0-verification-content-blocks-20261113`。包位于 `build/verification/blocks-signed/maven-central-bundle.zip`，SHA-256 为 `e942728b349e767eea565d1e7e9f60e615c8c8c2cb987f06f6447071a5ac7cf0`；同目录 SBOM 的 SHA-256 为 `afe9cbb101ce765d81f05090db19c8cbe79aa19dc705d988d69725e2b3a12437`。它们是本地验证身份，不是已上传版本。

独立工具使用固定环境中的 pypdf 6.10.0、PDFium 5.13.0、Pillow 12.3.0，公式 SVG 比较使用 resvg-py 0.5.0。完整七页进阶基线、生产和能力验证脚本通过；公式样例的七处矢量／原生 Word 公式及四处制品消费公式通过原有独立验证。字体缓存在本批签名制品的元数据和仅 POM 两条路径中各重新检查六种状态，文字／几何及双引擎同引擎像素一致；没有在本批重新采样性能。

可复现输出和脱敏汇总位于 `build/verification/block-content-20261113`，签名输入清单／分发报告位于 `build/verification/blocks-signed`。共享文档只保留行为与摘要，不收录私有日志、字体二进制或个人路径。

## 剩余边界

源格式明确选择 TEXT／MARKDOWN／HTML，公式和表格分别开关，不猜测混合语法。只接受既有严格子集和显式资源；HTML 公式不跨文本节点，也不处理属性、链接或代码中的公式。复杂度预留是输入政策，不承诺任意解析器的堆上界。

正文源格不扩展重复表头的块格能力；`content`／`scope` 不是 XML 页眉／页脚的直接子节点。段落没有新增通用背景和边框，继续使用已有表格／组件能力。源矢量导出 DOCX 默认拒绝，必须显式允许转逻辑文字；原生 Word 数学仍使用专门数学入口。本次没有新增 Word／浏览器人工编辑或 PDF/A 合规验收，也没有 RSS／通用性能承诺。
