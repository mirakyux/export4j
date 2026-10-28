# 绘制透明度与 SVG 仿射变换

绘制列表支持独立的填充透明度与描边透明度。PDF、SVG 和 PNG 后端使用同一显式状态，各次绘制按 source-over 合成；初始值均为 `1`。

```java
var drawing = new DisplayListBuilder(new PageSize(320, 180));
drawing.save()
        .opacity(0.5, 0.25)
        .fillRect(new Rect(10, 10, 80, 60), new RgbColor(1, 0, 0))
        .strokePath(path, RgbColor.BLACK, 4)
        .restore();
```

示例中的 `path` 是调用方提供的 `VectorPath`。几何单位为 pt，坐标原点在左上角。

`DrawingSurface.opacity(fill, stroke)` 与不可变 `DrawCommand.Opacity` 使用有限的 `[0,1]` 数值，`0` 完全透明、`1` 不透明。调用替换当前值，不与先前值相乘。`save()`／`restore()` 同时保存／恢复这两个值、矩阵和裁剪。新状态属于不绘制的命令，仍计入命令预算，不需要单独的无障碍内容绑定。

填充透明度作用于填充路径、矩形、文字及图像。图像自身 alpha 与当前填充 alpha 共同参与合成。描边透明度只作用于路径描边。轮廓文字的每个路径仍是一次独立绘制；本入口不建立离屏合成组。

PDF 使用独立的非描边／描边 alpha 图形状态，SVG 在最终元素上写入对应透明属性，PNG 使用 source-over。三个后端均保留嵌套状态恢复。PDF/A-1b 在写出之前拒绝非 `1` 的透明度；PDF/A-2b 的透明样例另用独立合规验证器检查。其他等级的完整支持范围继续以[标准 API](pdf-standards-api.md)为准。

## SVG 输入

`SvgComponent.parse` 的离线白名单增加以下属性：

| 属性 | 支持范围 |
| --- | --- |
| `fill-opacity`／`stroke-opacity` | 根、组与已有形状；数值或百分比，范围为 `[0,1]`；按 SVG 继承，局部值覆盖继承值 |
| `transform` | 根、组与已有形状；`matrix(a b c d e f)`、`translate(x[,y])`、`scale(x[,y])`、`rotate(angle[,cx,cy])`、`skewX(angle)`、`skewY(angle)` |

矩阵按源码顺序连接，支持嵌套组和有中心点的旋转，形状始终受原有 viewBox 裁剪。每个 `transform` 属性最多 64 个操作；原有 1,048,576 UTF-16 单位、10,000 元素及 64 层嵌套限制保持。非有限数、错误参数数量、未知操作和未消费的后缀拒绝。

```xml
<svg viewBox="0 0 320 180" fill-opacity="0.5">
  <g transform="translate(10 20) scale(2)" stroke-opacity="25%">
    <rect width="30" height="20" fill="#ff0000"/>
    <rect x="40" width="30" height="20" fill="#0000ff" fill-opacity="1"/>
  </g>
</svg>
```

第二个矩形的填充完全不透明；不会因根的 `0.5` 再被乘一次。

## 保留边界与验证

SVG `opacity`、离屏组透明度、混合模式、渐变、图案、滤镜和 CSS 仍明确拒绝，不用逐次绘制的 alpha 冒充组透明度。CMYK、专色和完整 ICC 绘制契约也不由本项提供。不执行脚本、外联或隐式资源加载，未知属性继续失败。

隔离调用方任务 `gap21Graphics` 生成 PDF／SVG／PNG 样例及拒绝控制，随后运行：

```powershell
python scripts/verify_gap21_graphics.py --consumer-build-directory <本次成功调用方构建目录>
```

独立验证器核对最终 PDF 的 alpha 资源、SVG 属性、嵌套状态恢复与实际颜色，使用 Poppler 和 PDFium 渲染全部新增 PDF，并核对导出的 PNG；透明 PDF/A-2b 另经 veraPDF 验证。它不把 XML 属性检查称作 SVG 的独立浏览器渲染。

本单元的构建、独立输出、调用方及容量覆盖见[阶段九十八](verification/phase-98-layout-text-streaming-and-graphics.md)。
