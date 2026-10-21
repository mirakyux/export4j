# 阶段三十九：独立列表标记样式

日期：2026-10-16。对应 GAP-04 与 XML 默认外观前置；[ADR-0046](../adr/0046-list-marker-styles.md)、[指南](../list-markers-api.md)。

模型／Builder／语义降低／模板绑定／DOCX 已实现独立完整标记样式，保留旧构造器和默认排版。新增 **34 项行为测试**：模型 10、绘制 11、DOCX 13。最终与阶段四十共同验收，全仓 **1,029 项测试通过，失败／错误／跳过均为 0**；编译及 Javadoc 无新增警告。

根编写样例时误用了 Document.Builder 不存在的 title 方法，定向构建报告编译失败，已改用显式 DocumentMetadata。旧失败日志保留。一次重跑碰到并行代理尚未完成的 DOCX helper 中间态，根停止构建并等待文件稳定，不将该次失败计作成功。

首次 DOCX 专用测试中，格式化夹具的字面 `%1.` 未转义，8 项在生成输入前失败；修为 `%%1.` 后全部通过，没有调整生产契约。最终只读审查还发现既有 bullet 提前返回会绕过外部 override 损失检查，已统一提前检查，并新增严格拒绝／允许损失诊断回归。

## 构建与调用方

最终 `build sbom publishLocalBundle` 及受影响样例通过，195 项任务、82 项执行，耗时 1 分 6 秒。`scripts/verify_local_consumer.ps1` 重新从最终制品编译、运行，成功目录为 `verification/consumer/build/validation-31f5262d2a6f`。前轮 `validation-d82e639ef6a7` 仅保留为修复前证据，最终检查使用新目录。隔离 SNAPSHOT 的既存 changing-modules 提示不属于新增编译警告。

~~~powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle `
    :examples:listMarkers :examples:xmlTextDefaults :examples:componentText `
    --no-daemon --console=plain
.\scripts\verify_local_consumer.ps1
build/verification/python-phase13/Scripts/python.exe scripts/verify_list_markers.py `
    --consumer-build-directory verification/consumer/build/validation-31f5262d2a6f
~~~

实际完整构建还运行 textPdf、flowPdf、tablePdf、componentPdf、advancedPdf、production、capabilities、authoring、themes、xmlThemes、xmlTemplates、xmlReferences、xmlIncludes、xmlBlockCells、xmlNumbering、xmlPageSettings、docxFields、styleDefaults、propertyStyles、contentsStyles、pageRegionFonts；精确命令及输出见 `build/verification/phase39-40-final-build.log`。

## 独立输出与边界

原文 2 页、DOCX 导入 2 页、隔离调用方 1 页，共 **5 页**独立检查通过：22 个实际文字运行、9 条仅属于标记的装饰线、2 个最终编号引用及两份标准 DOCX。检查嵌入字体实际 bold／italic／weight、字号／RGB、精确基线／推进、正文与标记样式分离、跨页继续、XYZ 引用、所选 level 的 rPr 和 marker-only 角色定义。

全部 5 页 Poppler／PDFium 双渲染，逐张打开完整 Poppler 页面，未见裁切、重叠或装饰污染正文。最大通道均值差 **0.251490**、变化像素比例 **0.001422**，低于 4／0.06；没有刷新基线。最终重新渲染的 8 页（含阶段四十）与已复查图片的 SHA-256 逐页一致。

最终 18 组历史独立检查和两组新输出检查通过，PDF/A 样例另经 veraPDF 合规检查。标准 DOCX 半 pt 量化、具名角色映射边界保持；两个 record 的新增分量形状不承诺兼容。有限 Latin 输出和自动 DOCX 往返不代表 Word 人工保存、通用样式继承或一般保真。后续整体判断见[首版评估](../readiness-assessment.md)。

证据：`phase39-40-final-{build,consumer,regressions}.log`、`phase39-40-tests.json`、`phase39-40-final-regressions.json`、`phase39-40-final-verapdf.xml`、`phase39-final-output.log`、`phase39-40-final-page-identity.json`，均位于 `build/verification/`；逐页图和报告在 `list-markers/`。
