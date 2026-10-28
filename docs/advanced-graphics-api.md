# 高级图形 API

绘制列表支持显式 CMYK、ICC 与命名专色、16 种混合模式、隔离透明组、线性／径向渐变和有界矢量图案。几何单位为 pt，左上角为原点。后端只消费绘制指令，不重新测量文字或分页。

## 颜色与画刷

`ColorValue` 是自有不可变颜色契约，现有 `RgbColor` 实现该接口。新增 `CmykColor(cyan, magenta, yellow, black)`、`IccColor(profileBytes, components)` 和 `SpotColor(name, tint, alternate)`。

颜色分量及专色浓度使用有限的 `[0,1]` 数值。ICC 数据与分量集合在构造时取快照，支持 1、3、4 通道的输入、显示、输出及色彩空间转换配置，PCS 必须为 XYZ 或 Lab。单配置最多 4 MiB，不读取系统配置或网络资源。专色使用显式 RGB、CMYK 或 ICC 备用颜色；浓度 `0` 为纸白、`1` 为完整备用颜色，禁止递归专色。

```java
var ink = new SpotColor("Package Orange", 0.8, new RgbColor(1, 0.4, 0));
var gradient = new Paint.Linear(
        new Point(20, 20), new Point(220, 20),
        List.of(new Paint.Stop(0, new RgbColor(1, 0, 0)),
                new Paint.Stop(1, new RgbColor(0, 0, 1), 0.4)));

var drawing = new DisplayListBuilder(new PageSize(300, 180));
drawing.fillPath(path, new Paint.Solid(ink))
        .fillPath(otherPath, gradient, DrawCommand.FillRule.EVEN_ODD);
```

示例中的 `path` 和 `otherPath` 是调用方提供的 `VectorPath`。`Paint.Solid`、`Paint.Linear`、`Paint.Radial` 和 `Paint.Tile` 均为不可变画刷。渐变使用 2～256 个有序色标，包括 `0`、`1` 两端，允许同位置色标表达颜色跳变。色标自身透明度与当前绘制透明度共同参与合成。

线性渐变接受起终点；径向渐变接受圆心、严格位于圆内的焦点与正半径。`PAD` 延伸端色，`REPEAT` 重复，`REFLECT` 镜像重复。画刷矩阵将画刷坐标映射到当前绘制坐标，必须在数值精度内可逆。

`Paint.Tile(bounds, xStep, yStep, transform, commands)` 重复一个带裁剪的矢量单元。命令和边界使用画刷坐标；单元必须完整位于不超过 14,400 pt 的正重复步长内，允许单元之间留白。它不依赖外部图像或可执行回调。单元和透明组内的 `Save`／`Restore` 独立平衡，不能恢复外部状态。

## 混合、透明组与裁剪

`blend(mode)` 替换当前逐次绘制的混合方式，并由 `save()`／`restore()` 保存。支持 `NORMAL`、`MULTIPLY`、`SCREEN`、`OVERLAY`、`DARKEN`、`LIGHTEN`、`COLOR_DODGE`、`COLOR_BURN`、`HARD_LIGHT`、`SOFT_LIGHT`、`DIFFERENCE`、`EXCLUSION`、`HUE`、`SATURATION`、`COLOR` 和 `LUMINOSITY`。

`group(bounds, commands, opacity, blend)` 将子命令在透明背景上合成，再以组透明度乘外层填充透明度绘制一次。子命令从透明度 `1`、`NORMAL` 混合开始，继承外部矩阵与裁剪，并受组边界裁剪。子图形重叠处不会重复乘组透明度。外层逐次混合状态不覆盖组显式指定的混合方式。

`clipPath(path, rule)` 支持非零和奇偶绕组，允许孔洞；连续裁剪相交。变换、路径裁剪、逐次透明度、混合方式和状态恢复可组合使用。状态命令不需要无障碍绘制内容绑定；组作为一次绘制绑定，组内不额外推导语义阅读顺序。

## 各后端的保留与损失

| 能力 | PDF | SVG | PNG |
| --- | --- | --- | --- |
| CMYK／ICC／专色 | 保留原生颜色空间、ICC 字节和 Separation 名称 | 显式转为 RGB 外观并报告颜色空间损失 | 同左 |
| 渐变 | 原生函数着色；色标透明度使用灰度软蒙版；支持三种延伸方式及偏心焦点 | 本地渐变定义、色标与画刷变换 | 按显式 DPI 采样，保留色标透明度 |
| 矢量图案 | 原生 tiling pattern；正确组合页面／组／形状矩阵及绘制透明度 | 本地图案定义与嵌套绘制 | 按目标变换与 DPI 生成有界单元 |
| 混合与隔离组 | 原生混合状态和隔离 Form 组 | `mix-blend-mode` 和隔离组 | RGB 合成，包含非分离混合方式 |

设备 CMYK 没有隐式印刷配置。SVG／PNG 的设备 CMYK 降级使用 `(1-C)*(1-K)` 等显式近似；ICC 通过提供的配置转换，专色通过备用颜色转换。命名油墨和印刷分色不能由 RGB 像素恢复，不承诺设备 CMYK 在不同阅读器／印刷机上的颜色一致。

PDF 图案保持矢量。外层透明度或混合方式存在时，将完整图案绘制写入隔离 Form 后再合成一次；图案单元仍使用原生重复资源，防止阅读器重复应用透明度。

三个具体后端均提供接收 `ProcessingPolicy` 并返回 `ProcessingReport` 的 `write(document, output, policy)`；PNG 单页另有 `writePage(page, output, policy)`。默认严格拒绝无法保留的颜色空间。允许降级时使用 `DEGRADE`，可选择 `WARN` 或 `IGNORE`；忽略仅隐藏警告，损失仍在报告中。报告只在输出成功后返回 `ACTUAL`，输出流始终由调用方拥有。

```java
var policy = ProcessingPolicy.builder()
        .feature("spot-color", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DEGRADE)
        .build();
ProcessingReport report = new SvgBackend().write(document, output, policy);
```

稳定特征键为 `cmyk-color`、`icc-color`、`spot-color`。PDF 渐变原生保留同一种颜色空间；混合不同颜色空间的色标使用 `gradient-color-space` 策略，允许后统一转为 RGB 插值。RGB 归档输出中不适用的设备 CMYK，以及 PDF/A-1 的 ICC 版本限制使用 `archival-color-space` 策略。PDF/A-1 不接受透明组、非正常混合、透明图像或非全不透明的绘制／渐变；这类归档限制不由颜色降级绕过。实际归档等级仍以[标准 API](pdf-standards-api.md)及对应合规证据为准。

预算、非法输入、取消和 I/O 错误始终失败，不进入忽略分支。新增嵌套资源参与命令、图片、文字和路径预算；递归资源最多 32 层，保存状态与资源层级合计最多 64 层。静态后端限制累计 200 万条命令、500 万段路径、5,000 万源图片像素及 200 万文字码点；ICC 使用量有限。PDF 继续使用 `PdfLimits` 的累计上限，并限制累计色标和 ICC 字节。PNG 中间图像累计最多 1 亿像素。

## SVG 离线输入

`SvgComponent.parse` 在原有基本形状、绝对 `M/L/C/Z` 路径和仿射变换基础上，支持以下本地矢量结构：

| 输入结构 | 支持范围 |
| --- | --- |
| `defs`、`id`、`use`、`symbol` | 本地 `href`／`xlink:href`，显式位置，symbol 视口及比例对齐；重复 ID、悬空／循环引用失败 |
| `linearGradient`／`radialGradient` | 继承引用、色标、色标透明度、用户坐标／对象边界坐标、三种延伸方式与画刷变换 |
| `pattern` | 继承内容、本地矢量单元、用户／对象边界单位、内容单位、viewBox 与变换 |
| `marker` | 起点、中间、终点；直线及三次曲线切线，自动方向／起点反向／固定角度；尺寸、参考点、视口及 context-fill／context-stroke |
| `clipPath` | 基本形状和本地 `use`，多个形状取并集；非零／奇偶孔洞、对象边界单位、变换及 clipPath 上的嵌套裁剪 |
| 透明与样式 | 继承 fill／stroke 透明度；独立组 `opacity`、混合与隔离；白名单内联样式声明 |

根 `viewBox` 必填，组件按可用区域保持比例。形状、透明组和定义均保持当前矩阵与裁剪。解析限制为 1,048,576 个 UTF-16 单位、10,000 个元素、64 层组、100,000 次展开及 32 层资源引用；定义引用带循环检测，不通过展开绕过预算。

SVG 输入仍是离线矢量子集，不实现浏览器 CSS、滤镜、脚本、动画、外部文档、任意路径语法或文字布局。只接受白名单属性和内联样式，非法或不支持的语法明确拒绝。源 SVG 在独立阅读器不支持的扩展写法不能作为保留证据。

## 验证入口

隔离调用方任务 `gap21Complete` 生成颜色、渐变／图案、16 种混合、透明组、SVG 导入及高级图形归档样例。运行：

```powershell
python scripts/verify_gap21_complete.py --consumer-build-directory <本次成功调用方构建目录> --chromium <Chromium可执行文件>
```

验证器使用 pypdf 检查原生资源，Poppler／PDFium 双渲染最终 PDF，resvg 渲染最终 SVG，并以隔离 Chromium 配置检查源 SVG 的实际呈现。resvg-py 仅为独立验证依赖，采用 MIT 许可；其 resvg 渲染器采用 MPL-2.0，均不加入 Java 运行时制品。完整验证还要求 veraPDF；临时图像、浏览器配置和原始报告位于忽略的 `build/verification/`。

架构取舍见 [ADR-0100](adr/0100-native-paints-and-offline-svg-resources.md)。
