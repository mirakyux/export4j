# ADR-0098：显式逐次绘制透明度与离线 SVG 仿射变换

- 状态：采纳
- 对应缺口：GAP-21.1、GAP-21.2 的透明度与变换子目标

## 问题

原绘制契约只有不透明 RGB。图像自身的 alpha 不能表达矢量填充、描边或文字的透明度；SVG 输入也无法保留显式仿射变换和填充／描边透明属性。

## 决策

在后端中立的绘制列表中加入 `DrawCommand.Opacity(fill, stroke)`，由 `DrawingSurface.opacity` 与 `DisplayListBuilder` 记录。状态初始为 `1/1`，使用有限的 `[0,1]` 值并替换旧值；Save／Restore 保存和恢复。该命令不绘制，计入命令预算，无须无障碍语义绑定。

填充值影响填充路径、矩形、文字和图像，描边值仅影响路径描边。每次绘制独立按 source-over 合成。PDF 使用非描边／描边 alpha 状态；SVG 将值写入最终元素，避免嵌套组错误累乘；PNG 使用显式 source-over。后端消费既定几何，不重新布局。

SVG 白名单增加可继承的 `fill-opacity`／`stroke-opacity`，局部值覆盖继承值；支持标准仿射 transform 列表，保留顺序、嵌套、viewBox 裁剪及有限解析预算。SVG `opacity` 需要离屏合成组，不能等价替换成后代 paint alpha，继续拒绝。

## 结果与边界

PDF/A-1b 在写出前拒绝透明状态。PDF/A-2b 的最终透明文件须通过独立合规检查。CMYK／专色／完整 ICC、混合模式、离屏组、渐变、图案、滤镜和完整 CSS 继续开放，不由本子目标关闭。

公共 sealed 绘制命令新增类型，使用穷举 switch 的调用方需要补充状态分支；原有默认不透明绘制保持。共享静态转换保留该命令，绘制命令索引不变。几何语义处理不把透明状态当作矩阵或裁剪。

验证由独立调用方生成 PDF／SVG／PNG，核对 alpha 资源与属性、嵌套恢复、覆盖继承、图像 alpha 组合、实际像素及非法输入拒绝；PDF 使用两个独立渲染器，归档结果使用 veraPDF。XML 属性检查与实际 SVG 阅读器渲染分别登记。使用方法见[透明度 API](../graphics-opacity-api.md)。
