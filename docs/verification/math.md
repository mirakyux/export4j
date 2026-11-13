# 可选公式模块验收

本记录对应 [ADR-0113](../adr/0113-optional-bounded-math.md)和[公式 API](../math-api.md)。公式转换属于当前开发版，不属于已发布的 `0.1.0-rc.3`。测试只证明已声明的有限子集，未增加 OCR、完整 LaTeX 或任意 Word 文档兼容性。

这是公式模块初始批次的验收记录，保留当时结果和限制。后续标题／列表混合内容、重复表头原生公式、双目标编译及真实文献输出见[富内容验收](rich-content.md)，当前调用契约见[混合内容 API](../rich-content-api.md)。

## 复现

Java 21，使用仓库 Wrapper；Python 使用 `scripts/requirements-verification.txt` 对应隔离环境，另需 Poppler。

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:math
powershell -NoProfile -File scripts/verify_local_consumer.ps1 -VerificationPython <verification-python>
python scripts/verify_math.py --consumer-build-directory <successful-consumer-build>
python scripts/verify_driverless.py --consumer-build-directory <successful-consumer-build> --output-directory build/verification/math-driverless
```

`MathExample` 使用公共模块，生成一页 PDF／SVG／PNG 和可编辑 DOCX。内容含正文混排、分式／根式、上下标、独占段公式、正文表格、重复公式和显式中文字体。独立 `MathConsumer` 从实际 Maven 制品生成四式样张，额外核对唯一类提供者、制品摘要、上下文保护、降级记录及预算不可绕过；不使用当前源码 classes 替代制品。

## 回归重点

- 完整源码和递归参数预算在调用第三方排版前生效；未知命令前缀不能绕过后续预算。
- 取消、超时和总几何预算不进入源码降级；同线程恢复后可以继续处理。
- 原 Markdown UTF-16 范围、反斜线奇偶、CRLF、代码／链接／HTML 保护，以及导入告警／错误的原文映射。
- 重复公式各有真实锚点；表格正文的原生数学可严格写出／读回，临时资源键不进入成功输出。
- 当时清除无锚点的临时单元格段落 ID，避免普通重复表头被错误提升为块；当时原生锚点／书签与块表头仍拒绝。后续原生公式的物理单元格绑定见富内容验收，块表头限制另行保留。
- 五款明确 Computer Modern 字体、曲线控制点／基线边界、重叠轮廓并集和有限并发；不使用隐式系统字体。
- 本地 JAR／POM／Gradle 元数据、BOM／SBOM、源码材料及许可；独立 Gradle 元数据和 POM-only 消费分别核对。

独立输出验证核对 PDF 的 TeX ActualText 顺序、曲线路径和零公式图像对象；DOCX 的标准 `m:oMath`、分式／根式／上下标以及无图片／无临时标记；SVG 的阅读标签和无图片。全部样张页面使用 Poppler／PDFium 渲染，并与 resvg／RasterBackend PNG 比较。完整图像另行复查，不能只检查接触表缩略图。

## 边界

Word 原生转换子集小于矢量排版；矩阵、求和／积分的运算对象推断及伸缩定界符在原生入口明确拒绝。初始入口没有默认图像重试链，显式 `KEEP_SOURCE` 逐式保留字面源码并报告；当时标题／列表条目中的公式拒绝。后续 `MathContent` 支持混合标题／列表和显式逐目标 IMAGE／TEXT 候选，默认仍严格失败。

库内部的锁无法阻止应用直接修改 JLaTeXMath 全局状态；该共享用法不支持。有限并发与有界检查不证明强制抢占沙箱或长期无泄漏。独立工具及标准 OOXML 验证不等于本次已经完成 Word 界面编辑／保存兼容性验收。

另有既有 DOCX 列宽边界未在本次修改：A4、54 pt 边距的满宽分数列写出后，若移除 Export4j 扩展元数据再严格读入／写出，twip 量化后的固定列宽与正文宽度可能相差一个浮点 ULP，触发超宽检查。此问题属于列宽恢复与严格比较；本次数学锚点回归使用显式 200 pt 列，未覆盖该往返情形，不能据此宣称已修复。

## 本地结果

本批本地验收已完成，原始日志和完整文件摘要保留在被忽略的 `build/verification/`，不作为公共源码或正式发布内容。

| 检查 | 结果 |
| --- | --- |
| `build sbom publishLocalBundle :examples:math apiCheck` | 通过；全仓 JUnit 结果 2,483 项，0 失败／错误／跳过，其中公式模块 34 项 |
| API 与固定 RC2 基线 | 17 模块、753 个可访问类型；0 不兼容声明、30 项增加、0 第三方 API 类型泄漏 |
| 隔离开发版消费 | Gradle 元数据和 POM-only 均通过，实际生成四种公式输出 |
| 本地临时签名分发 | 17 库＋BOM，87 项制品／签名通过；篡改被拒绝，临时私钥已删除 |
| 签名制品的两种隔离消费 | 均解析 17 库及 BOM，并核对全部自研 JAR 摘要；运行类提供者唯一 |
| 无打印驱动运行 | 17 个自研库无打印 API 字节码引用，4 个消费入口未加载打印类；3 项打印 API 负控制均被拒绝 |
| 公式输出 | 主样张 1 页／7 式，两种制品消费各 1 页／4 式；全部 PDF 页双渲染并复查，标准 OMML 结构通过 |
| 文档站／规则 | 411 页构建、7 项站点回归及规则检查通过；保留已有大分块构建提示，未部署 |

主样张 Poppler／PDFium、RasterBackend／PDFium、resvg／PDFium 在模糊 1px 的协议下，平均通道误差分别为 0.359039、0.125159、0.150870，变化像素比例分别为 0.004378、0.001247、0.001238，均低于既有阈值 4／0.06。完整页面未发现公式裁切、重叠或缺字；原文提取、曲线与零公式图像分别核对。

可复现的本地签名验证命令（临时身份，不会上传）：

```powershell
python scripts/verify_signed_distribution.py --version 0.0.0-verification-math-20261113 --native-classifier natives-windows --output build/verification/math-signed-final
```

该批公式 JAR 坐标为 `com.mirakyux.export4j:export4j-math:0.0.0-verification-math-20261113`，只存在于本地验收仓库；SHA-256 为 `332149a8f9d9e862e89d0f58fb7208fbf264502889e3557937446963102eebdf`。其路径为 `build/verification/math-signed-final/repository/com/mirakyux/export4j/export4j-math/0.0.0-verification-math-20261113/`。签名包及消费者 JAR 摘要见 `build/verification/math-signed-final/distribution-report.json`，源码输入清单见同目录的 `build-inputs.json`。此坐标不能写成公开可下载版本；开发接入仍使用本地 `0.1.0-SNAPSHOT`。

本次没有重新执行 Word 界面编辑／保存，也未复跑既有私有真实文献 17 页或作性能比较；本次证据来自公开自建数学样例及正式构建出的隔离消费制品。
