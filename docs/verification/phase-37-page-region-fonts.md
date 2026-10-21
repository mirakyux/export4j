# 阶段三十七：固定页区字体角色

日期：2026-10-16。对应 GAP-04；[ADR-0044](../adr/0044-page-region-font-roles.md)与[使用指南](../page-region-fonts-api.md)。本单元已完成本地验收。

修复 `ComponentDocumentRenderer → PageComposer → ComponentContext` 在固定页区丢失具名字体角色的问题。正文、最终字段和页区共用布局入口的不可变角色快照，页眉、页脚、背景和前景收到真实字体组及其缺字策略。原有无角色合成重载继续提供空映射；默认字体组、六项文字继承、分页预留、交互、预算和固定语义内容拒绝保持。没有新增依赖或字体资源。

新增 **11 项行为测试**，全仓 **948 项，失败／错误／跳过为 0**。实际验证四类页区的字体 ID、正文默认组分离、目录偏移、分节重启及首页／奇偶模板的最终上下文、两种 scope 嵌套、同步回调修改原 map 后的快照、24 组并发布局隔离、旧重载精确兼容、null 预检和缺字 UTF-16 页区诊断。多片段固定组件及四类页区中的延迟 SemanticTable 继续拒绝。最终 InlineField 使用相同快照由源码及历史字段回归确认，新增快照测试没有单独构造字段用例。

## 验证与输出

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle `
    :examples:pageRegionFonts :examples:componentPdf :examples:advancedPdf `
    :examples:production :examples:capabilities :examples:styleDefaults `
    :examples:propertyStyles :examples:themes :examples:contentsStyles `
    :examples:xmlPageSettings --no-daemon --console=plain
scripts/verify_local_consumer.ps1
build/verification/python-phase13/Scripts/python.exe -X utf8 build/verification/run_phase37_regressions.py
build/verification/python-phase13/Scripts/python.exe -X utf8 -X faulthandler scripts/verify_xml_references.py
```

完整构建 181 项任务、76 项执行，耗时 1 分 29 秒；编译、Javadoc、SBOM 和本地 bundle 通过。隔离调用方为 `verification/consumer/build/validation-ba73afe228f6`，只使用本地 Maven 制品。未出现 Java 编译或 Javadoc 警告；进阶样例仍输出预期的两项缺字诊断，隔离 SNAPSHOT 锁定仍有既存 changing-modules 提示，不作为正式发布稳定性证明。

17 组独立检查最终通过：固定页区、XML 页面／原模板／引用／include／块格／主题／编号、Markdown、目录、DOCX 字段、生产、能力、进阶、组件默认样式、逐属性样式和 Java 主题。PDF/A 通过独立 veraPDF。首轮并行回归中 XML 引用 Python 进程以 Windows 状态 `3221227274` 异常退出且无日志；同一固定环境、同一文件及断言单独重跑通过，未修改生产或降低阈值。原因未确定，首轮失败、重跑日志及对账记录均保留；不宣称首轮全部成功或长期稳定。

| 输出 | 页数 | 独立检查 |
| --- | ---: | --- |
| 仓库样例 | 2 | 四类页区真实粗体／不同颜色；常规正文与普通页码；物理索引 0／1，显示页号 7／8，总数 2 |
| 隔离调用方 | 2 | 独立 320×260 pt 几何；四类页区真实粗体；显示页号 3／4，总数 2 |

检查 PDF 嵌入 TrueType 程序的粗体标志与字重、实际字号／颜色／CID advance、由源字体 hhea／head 独立计算的精确基线、填充文字模式、PDFium 完整标记搜索和实际字框、正文页带预留。SVG 检查根／页 viewBox、原点和堆叠位置、累计矩阵、真实字形路径及颜色／可见状态／控制点边界。SVG 检查不等于通用 SVG 解析或浏览器兼容证明。

全部 **4 页**经 Poppler／PDFium 双渲染，逐张查看完整 Poppler 页，无可见裁切、重叠或丢字。最大通道均值差 **0.734788**、变化像素比例 **0.004524**，低于 4／0.06；未刷新视觉基线。

脚本独立审查保留了首轮 20 项变异中漏检 9 项的证据；补齐精确基线、不可见文字和 SVG 真实绘制断言后，4 项正例控制与全部 20 项变异符合预期，额外 6 项 SVG 根隐藏／变换变异全部拒绝，原件摘要未变。检查脚本首轮 PDFium search 错用上下文管理器的异常已修正，失败日志保留。

## 复评与未完成范围

现有 StyleScope／FontScope 本来就能配置页区字号、固定行距、颜色和默认字体；本次修复让可信自定义组件可复用引擎注册角色。标准 TextComponent 尚无角色 setter，仍需一个显式委托组件。GAP-04 保持部分支持，不能将此登记为完整品牌主题、标准富文本页区、XML 页区或语义表格页区已完成。

下一优先切面是模板／主题的默认字体角色及颜色：正文与标题使用不同真实字体，普通文字、未声明 span、链接、字段和编号前缀的覆盖／恢复规则需要统一设计。随后推进可读的富文本页区及 XML 页区入口；递归表格续排、流式更多内容、长期容量和 Word 保存回读仍各自验收。OCR 排除。

证据：`build/verification/phase37-{focused,full-build,consumer}.log`、`phase37-tests.json`、`phase37-regressions.json`／`phase37-regressions-reconciled.json`、`phase37-xml_references-retry.log`、`phase37-verapdf.xml`、`phase37-review-mutations{,-2}.json`、`phase37-review-root-states.json`、`phase36-37-source-audit.json`、`page-region-fonts/report.json`／`manual-review.md`。
