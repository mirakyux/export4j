# 阶段一百零四：离线 HTML／CSS 布局

本阶段对应 GAP-18.1，验收扩展的静态选择器、级联、媒体／分页规则、盒尺寸、Flex／Grid 和定位。默认语义导入与显式运行时布局模式分别执行；支持范围以 [HTML 导入指南](../html-import-api.md)及 [ADR-0104](../adr/0104-bounded-html-css-layout.md) 为准。

## 实现与检查项

- 选择器增加属性、兄弟、静态伪类、nth 公式及有界 is／not／where；important、函数特异性、源码顺序和相互独立的 viewport／@page 分别回归。
- 真实 Flex grow／加权 shrink／min-max 冻结、行换行及 align-content、列向布局、Grid 轨道／合并／显式占位、padding／box-sizing 和 relative／absolute 由最终几何验收。
- 文本由现有语义测量生成，逻辑来源顺序与视觉位置独立，实际链接矩形在最终变换后核对；内部测量桥采用真实无边框表格。
- 严格拒绝、WARN／IGNORE 预检与实际损失一致；内在 Flex 的块流降级、跨格式运行时组件移除均检查实际内容。脚本／事件／未命中条件内外部资源、工作预算和取消不可忽略。
- 原子文件输出在取消及输出上限失败后保留旧目标，删除临时文件；默认语义表格边框同时检查 HTML 重新导入与 Markdown 显式损失。

## 可复现入口

从仓库根目录运行；Windows 使用 `gradlew.bat`，Linux／macOS 使用 `./gradlew`。

```text
gradlew.bat :export4j-adapter-web:test
gradlew.bat build sbom publishLocalBundle
powershell -File scripts/verify_local_consumer.ps1
```

隔离 Maven 调用方的 `htmlCssLayout` 任务使用本次成功发布的本地制品。其输出位于对应 consumer build 目录的 `output/html-css-layout/`。在本次成功的 consumer build 目录执行专项任务，再追加独立检查：

```text
gradlew.bat -p verification/consumer -PconsumerBuildDirectory=build/<本次成功的consumer-build目录名> htmlCssLayout --refresh-dependencies --write-locks --no-daemon --console=plain
python scripts/verify_html_css_layout.py --consumer-build-directory <本次成功的consumer-build目录> --chromium <Chromium可执行文件>
```

验证器将原始 HTML 包装为显式字体／密度的独立浏览器样例。此包装器的本地字体数据只用于验证，不能作为库接受 CSS 外部资源的证据。8 个场景覆盖 Flex 伸缩／换行／列向、Grid／跨行、定位和 body 布局；对应 PDF 与 SVG 分别用 Poppler、PDFium、resvg 渲染，与 Chromium 源盒位置及背景几何核对。另检查 3 页分页、独立超链接及 WARN／IGNORE 的实际块流结果。

每个场景的背景色只标识一个矩形，文字显式留出左内边距。背景比较只填充封闭的字形孔洞，保留所有与外部连通的空白；不使用跨盒扫描行包络。位置边界允许 2 个 144 dpi 像素，背景交并比须大于 0.975。缺盒、错误宽度、错误间距及同色背景中间裂开四项负控制都必须被拒绝，防止掩盖几何错误。

Chromium 与 Export4j 的段落首行基线不承诺像素一致；实测相同字号与行距仍有基线差异。因此背景几何与文字分别检查：正文 token、唯一性、逻辑顺序和最终链接矩形独立核对；最终 PDF 的双渲染及 SVG 渲染继续比较完整画面，包括文字，不把字形差异全部屏蔽。

生成物、工具版本、逐图几何／渲染指标及联系表保存在忽略目录 `build/verification/gap18-current/`；不纳入公共源码。

## 验收状态

本次成功构建的制品由全新隔离 Maven 调用方运行 `htmlCssLayout`，专项执行通过。Web 模块 6 个测试类、91 项测试通过，失败、错误、跳过均为 0；其中 10 项布局测试覆盖级联／媒体、真实几何、策略、预算与边界。统一构建和 Maven 制品整体对账分别记录在[阶段一百零五](phase-105-publication-and-compatibility.md)，本节只登记此专项的最终输出。

最终 **14 页 PDF、8 份 SVG** 验收通过。8 个浏览器源场景、全部 PDF 页的双渲染及 SVG 渲染共 44 张图完成复查；最终新调用方的 8 张联系表与已复查图片逐一 SHA-256 一致。没有自动更新视觉基线。

| 检查 | 实际结果 |
| --- | --- |
| Flex／Grid／定位／body 背景几何 | 三种最终渲染分别对照 Chromium；背景交并比最低 0.997076，全部边界通过 2 像素上限 |
| 最终完整画面 | PDF 双渲染与 SVG 核对，包括文字；8 个主场景平均通道误差最高 0.156873／255，变化像素比例最高 0.001905 |
| 分页／文字／链接 | @page 产生 300 × 240 pt 三页；正文唯一性和顺序通过，最终真实 URI 与链接矩形独立核对 |
| 策略／安全／预算 | 6 项实际损失，WARN／IGNORE 的预检与实际报告、块流降级及跨格式组件移除一致；8 项拒绝控制通过 |
| 原子文件 | 取消和输出上限失败均保留旧目标，临时文件为零 |
| 验证器负控制 | 缺盒、错宽、错间距、同色背景裂开四项全部被识别 |

独立工具为 pypdf 6.10.0、PDFium 5.13.0、Poppler 26.07.0、resvg-py 0.5.0、Pillow 12.3.0，浏览器为 Chromium 系列 157。几何验证使用显式 144 dpi，不根据运行机器寻找字体。

## 边界

这是一套明确的文档 CSS 子集，不是浏览器兼容实现。原子盒不进行跨页 Flex／Grid 碎片化；百分比高度、内在轨道、复杂基线、固定／粘滞定位、重叠网格、溢出裁切及脚本／资源加载继续拒绝或按实际功能报损。运行时布局组件不能作为普通语义文档无损编辑或互转；PDF/UA 结构和不同阅读器的排版兼容不由本阶段推断。
