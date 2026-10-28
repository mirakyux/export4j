# XFA 离线读取与静态扁平化

`PdfAdapter.xfa(input)` 是专用 XFA 入口。`read()` 读取模板、实际 datasets 绑定值、页和字段几何；`flatten(font, size)` 按声明的布局子集创建新的静态 PDF。普通 PDF 读取、复制和对象编辑入口继续拒绝 XFA，避免把动态表单误认为已经验证的静态页面。

```java
import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfFont;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.nio.file.Files;
import java.nio.file.Path;

var adapter = new PdfAdapter();
var input = adapter.input(Path.of("xfa.pdf"), "");
var snapshot = adapter.xfa(input).read().snapshot();
var policy = ProcessingPolicy.builder()
        .feature("XFA_INTERACTIVITY", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DISCARD)
        .feature("XFA_PAGE_RECONSTRUCTION", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DEGRADE)
        .feature("XFA_FONT_MAPPING", ProcessingPolicy.Action.WARN,
                ProcessingPolicy.Treatment.DEGRADE)
        .build();
var font = PdfFont.of(Files.readAllBytes(Path.of("explicit-font.ttf")));
adapter.xfa(input).policy(policy).flatten(font, 10)
        .to(Path.of("xfa-static.pdf"));
```

## 声明子集

| 结构 | 当前行为 |
| --- | --- |
| 容器 | PDF XFA name／stream packet 数组，或单个 XDP 流；模板 2.5／2.6／2.8、3.0—3.3；datasets 1.0 |
| 页 | 一个 pageArea／medium／contentArea，明确页尺寸及内容区；支持声明的横向尺寸 |
| 静态布局 | position subform、明确 x／y／w／h、textEdit 文本字段及标量 draw、明确左侧 caption 和 reserve |
| 动态布局 | 根 subform 的 tb 流布局；子 subform 按 occur 及重复 datasets 节点展开，按声明高度分页 |
| 数据 | 字段名及有限点分路径、`$record.`／`$.` 前缀的局部路径；文本、数值、日期等标量按源文本读取 |
| 单位 | pt、in、mm、cm 转换为物理 pt，快照从左上角计量，页索引从零开始 |

`Snapshot`、`Page`、`Field` 及列表均为不可变投影。动态样例 8 条 30 pt 行在 160 pt 内容区分页为 5＋3 条；静态 packet 与单流 XDP 均实际读取绑定后的 datasets 值。嵌套 tb、任意 SOM 表达式、多 pageArea、非文本控件和自动适应布局不在此子集中；遇到这些结构直接失败，不以静态页代替实际数据或虚构排版。

## 扁平化与损失

扁平化完整嵌入调用方 TrueType，以一个显式字号绘制文本，按字段宽度换行并预检查高度，缺字或放不下即失败。文本字段绘制普通边框，draw 绘制标量内容；没有阅读器运行时、系统字体扫描或脚本计算。

默认严格拒绝以下损失，调用方必须显式选择：

| feature | treatment | 实际结果 |
| --- | --- | --- |
| XFA_INTERACTIVITY | DISCARD | 移除 XFA 模板、数据绑定及表单编辑能力 |
| XFA_PAGE_RECONSTRUCTION | DEGRADE | 按声明模板重新建页，不复制源 PDF 外壳及其其他对象 |
| XFA_FONT_MAPPING | DEGRADE | 调用方字体、固定字号及统一文本外观替代模板样式 |
| XFA_PACKETS | DISCARD | 不重建未支持的被动 packet |
| XFA_LAYOUT | DISCARD | 不重建被动布局子元素 |

因此输出不承诺原文件背景、边框、字体、颜色、静态资源、标签、书签、附件、元数据或加密完整保持。WARN／IGNORE 的输出行为相同，IGNORE 仍保留实际损失。加密源须允许 owner 解密及 `ENCRYPTION_REMOVED / DEGRADE`。

脚本、事件、提交／执行／连接、外部引用及模板 use／usehref 无论策略均拒绝；DTD／实体禁用，解析器不联网。输入图、全部 packet 解码、XML 深度／节点／属性／文字、重复展开、字段、分页、字体、绘制及输出均有预算，支持同步取消和旧目标原子保护。

## 验证边界

独立输入验证静态 packet、单流 XDP、真实重复数据流分页及脚本／外部引用拒绝；输出由 pypdf 检查实际文本和页尺寸，Poppler／PDFium 渲染所有页。复现入口见[阶段九十四](verification/phase-94-pdf-extended-forms.md)。该 API 提供明确的静态／动态子集，不代表任意 XFA、JavaScript 或阅读器布局引擎兼容。
