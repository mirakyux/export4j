# 阶段四十：XML 默认字体与颜色

日期：2026-10-16。对应 GAP-04／07；[ADR-0047](../adr/0047-xml-text-defaults.md)及[指南](../xml-text-defaults-api.md)。

独立 TextDefaults 已接入正文／列表、六级标题、单元格、未命名 span、链接和四类字段。局部属性按存在性覆盖，命名 span 保持完整值，直接块格继承与内表重新开域均已验收。新增 **20 项行为测试**通过，覆盖旧默认模型／布局、配置顺序、局部恢复、命名样式、四类字段、标记与正文首运行分离、嵌套格、动态行、图片、全部分支／片段、快照／失败回调、角色与 UTF-16、预算和取消。

首次样例将含交互链接的 DisplayDocument 直接交给 SVG，后端按已有契约明确拒绝。已在样例内显式另建无交互的静态预览，PDF 保留并检查全部链接；没有修改后端或静默丢弃语义。原失败日志保留。

## 最终验证

与阶段三十九共用最终构建：全仓 **1,029 项测试通过，失败／错误／跳过为 0**；195 项任务、82 项执行，1 分 6 秒，编译、Javadoc、SBOM、本地 bundle 通过。全新隔离调用方为 `verification/consumer/build/validation-31f5262d2a6f`，独立编译和执行成功；不是检查旧输出冒充本次结果。完整命令及受影响样例见[阶段三十九](phase-39-list-markers.md)。

~~~powershell
build/verification/python-phase13/Scripts/python.exe scripts/verify_xml_text_defaults.py `
    --consumer-build-directory verification/consumer/build/validation-31f5262d2a6f
build/verification/python-phase13/Scripts/python.exe build/verification/run_phase3940_final_regressions.py
~~~

样例 2 页和调用方 1 页共 **3 页**通过独立检查。核对实际物理字体、字号／RGB、精确基线、四类最终字段 `1／2／2／2`、8 条紫色字段轮廓及独立源字体墨迹边界、3 个链接框和两个 XYZ 锚点、嵌套格默认值，以及标准 OOXML 运行／编号属性。SVG 检查实际路径、控制点、颜色、视口、页偏移和完整逻辑顺序。

检查脚本首轮误把 `420.0` 等 SVG 尺寸当成字符串差异，并假定字段墨迹位于逻辑 g 内；按既有实际表示改为数值比较及 8 条直属实际路径的独立计数／几何检查，未改生产或放宽容差。交叉检查还证明原链接验证漏掉实际位置，补齐全部框／目标坐标断言；临时副本分别修改 Rect、Dest XY 后均被对应断言拒绝，正式 PDF／SVG／DOCX 哈希未改变。原失败日志及两项受控变异记录保留。

Poppler／PDFium 双渲染全部 3 页，根逐张打开完整页复查，未见裁切、重叠或丢字。最大通道均值差 **0.405779**、变化像素比例 **0.002077**，低于 4／0.06；没有更新基线。最终制品与最终调用方重新渲染后，8 页新输出（含阶段三十九）与已人工查看图片逐页哈希一致。

18 组历史独立检查全部通过：页区字体、XML 纸张／模板／字段／include／块格／主题／编号、目录、DOCX 字段、生产、能力、进阶、默认样式、逐属性样式、主题、常用编写和组件文字；加两组新检查共 20 组。PDF/A 样例由 veraPDF 独立验证。当前边界仍是有限 Latin 样例、离线模板及标准 DOCX 子集；本轮未重跑长期容量、Word 人工保存。XML 页区、模型全局完整主题和 HTML/CSS 继续开放，整体判断见[就绪评估](../readiness-assessment.md)。

证据位于 `build/verification/`：`phase39-40-final-{build,consumer,regressions}.log`、`phase39-40-tests.json`、`phase39-40-final-regressions.json`、`phase39-40-final-verapdf.xml`、`phase40-final-output.log`、`phase40-review-mutations.json`、`phase39-40-final-page-identity.json`；逐页图片和报告在 `xml-text-defaults/`。
