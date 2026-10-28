# ADR-0101：显式原生 OpenType 与静态 CFF 原生嵌入

- 状态：接受
- 日期：2026-10-28

## 背景

普通 Java 字体路径保持显式资源和确定性布局，但不能完整评价可变轴、CFF2、字体着色及通用 TTB 塑形。静态 CFF 已能测量和绘制轮廓，仍需独立的 PDF 原生字体嵌入以提供标准字体映射。

## 决策

增加可选 `export4j-opentype`，内部封装 LWJGL／HarfBuzz，应用显式部署匹配 native classifier。普通核心不传递平台二进制、不隐式加载 JNI；公开 API 不暴露第三方类型。字体实例不可变，无常驻原生句柄，所有 native 资源按同步操作关闭。

塑形、轴插值、彩色图像评价与行／列分页均在布局阶段完成，后端接收轮廓或带 alpha 图像。复杂脚本保留原文 UTF-16 簇，水平混排使用真实行框的逻辑锚点，竖排和单 run 保留原文 run 锚点；选区范围与逐 glyph 来源分别说明。纵中横是显式完整字素范围，不能静默猜测用户意图。

字体着色采用有界 paint 回调代理，预算同时覆盖最终图像、累计候选工作、中间画面计费和内嵌 PNG 解码；失败与取消回到 Java 后抛出。实际 Windows HarfBuzz 14.3.0 与 3.4.3 绑定头文件的 raster convenience API 不同，调用经过核对的 core paint SPI，并依据运行版本使用匹配 `get_funcs` 签名。

普通静态 CFF 的简单 run 在 PDF 后端完整嵌入 Type1C／CIDFontType0C，精确校验测量 GID 和 cmap 的一致性，并生成 ToUnicode；不再因为字体是 CFF 就强制转轮廓。复杂塑形、显式轮廓及 CFF 可编辑字段的原契约分别保留。

## 结果与边界

可变字体、CFF2、COLRv0／COLRv1、PNG／BGRA 位图、更多脚本与一般 TTB 排版拥有明确实现及独立验收入口。它们不使语义 DOCX／HTML 自动获得原生字体排版，彩色结果具有显式栅格分辨率，字体内 SVG 图像明确拒绝。

跨平台制品、完整 Unicode 方向数据库、任意外部字体和所有阅读器不从有限样例推断。构建、原文、独立塑形、提取、搜索、选区、双渲染和最终 PDF/A 分别登记，见[指南](../native-opentype-api.md)和[验收](../verification/native-opentype.md)。
