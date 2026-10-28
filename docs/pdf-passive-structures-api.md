# 外部 PDF 的图层、标签与兼容路径

外部 PDF 的读取、原对象编辑、渲染和语义恢复使用不同契约。一个对象能够保留或显示，不表示能够变成可编辑的段落；兼容处理必须返回实际损失，不能通过删除预检限制获得表面成功。

## 显式接受被动结构

```java
import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.nio.file.Path;

var adapter = PdfAdapter.builder().allowPassiveStructures(true).build();
var input = adapter.input(Path.of("source.pdf"), "");
var policy = ProcessingPolicy.builder()
        .defaultRule(ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .feature("PDF_TAGS", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
        .feature("DOCUMENT_METADATA", ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DISCARD)
        .build();
adapter.render(input.allPages()).policy(policy).prepare().toZip(Path.of("pages.zip"));
```

`allowPassiveStructures` 默认为 false。启用后仍完整验证输入及结构引用，不执行脚本、动作或外联。`read()` 保持静态读取边界，带批注的文档使用各自支持被动批注的 `readObjects()`、渲染、对象编辑及导入入口；开启图层／标签并不扩大批注类型白名单。

## 各路径的接受范围

| 内容 | 读取／检查 | 原文档保留编辑 | 页面渲染／光栅脱敏 | 语义恢复 |
| --- | --- | --- | --- | --- |
| 有效嵌入字体 | 提取使用已有 Unicode 映射；读取不是字体外观验收 | 未改内容保留源程序；文字替换另用显式字体 | 使用验证过的静态 TrueType、CFF、传统 Type1 及受控 Type3 路径 | 可恢复字形走段落路径；无法表达的受支持绘制可整页降为图片 |
| 无程序字体 | 普通提取不等于确定性绘制 | 不因读取成功放宽文字编辑检查 | 可显式登记受支持简单字体或水平 Type0 映射，原步进保持，替代外观报损 | 不隐式复用渲染映射，也不扫描系统字体 |
| 普通 Form XObject | 按调用提取，资源及递归有界 | 受支持文字匹配按调用复制后修改；共享实例不连带修改 | 使用源 Matrix、资源及裁切 | 普通不透明、正向等比文字子集可编辑恢复；复杂绘制可显式整页降级 |
| ExtGState | 不执行动作 | 白名单内普通不透明状态可进行文字编辑；其余按编辑策略保留原页 | 按源图形状态绘制；受资源及实际执行预算限制 | 普通不透明白名单保留；透明合成等不能表达时显式整页降级 |
| OCG／OCMD 图层 | opt-in 后使用默认 VIEW 可见性；关闭层文字不作为可见提取结果 | 不改图层的完整文档编辑保留源配置和引用；不是图层配置编辑器 | 只输出默认 VIEW，`PDF_LAYERS / DEGRADE`，输出不能切层 | 整页可见流图片，明确失去正文编辑和图层切换 |
| PDF 1.7 静态标签 | opt-in 后验证结构树、页／流／对象所有者和 ParentTree；普通文字提取不按标签重建阅读顺序 | 不改结构的完整文档编辑保留原树；带结构所有者的 Form 文字不克隆修改 | 外观可渲染，标签树不进入图片，`PDF_TAGS / DISCARD` | 支持的文字仍可恢复，标签、可访问性及逻辑结构明确丢弃；不把 Alt／ActualText 直接作为正文 |

字体及编码的准确范围见[字体兼容](pdf-font-compatibility-api.md)，正文替换仍受[文字编辑](pdf-text-editing-api.md)的匹配、状态和损失规则约束。元数据等无关编辑保留结构，不代表支持任意标签内部修改。

## 图层默认可见性

接受 `OCProperties` 的 OCG 清单、默认配置 `D`、被动备用配置 `Configs`，以及名称、Intent、顺序、锁定／单选组和受支持 Usage 字典。所有配置引用的组必须属于声明清单；冲突 ON／OFF、重复或缺失引用、错误类型均拒绝。

默认 VIEW 使用默认配置的 BaseState、ON／OFF，以及显式 View／ViewState。OCMD 支持 AnyOn、AllOn、AnyOff、AllOff 和由已声明 OCG 组成的 And／Or／Not 可见性表达式。嵌套标记内容、图片及 Form 自身的 OC 约束均影响可见结果。Print／Export 等被动配置可随原文档保留，当前入口不提供切换配置、用户身份、语言或交互式图层控制。

隐藏内容的绘制状态仍须正确执行，不能删掉隐藏层整段操作符后破坏后续可见内容的状态。默认视图图像中不包含关闭层，仍不等于源 PDF 已安全删除这些对象；要删除源内容使用[脱敏接口](pdf-redaction-api.md)。

## 标签验证与原对象保留

验证涵盖 StructTreeRoot／StructElem、父子 K、页 Pg、MCID、MCR／Stm、OBJR／Obj、StructParents／StructParent、ParentTree、RoleMap、ClassMap、IDTree 和 MarkInfo 的明确静态子集。结构树父子回指须一致；MCID 的页或流所有者、ParentTree 数组位置及对应元素须相符；对象引用须属于声明页面。编号／名称树检查顺序、范围、重复及引用，结构循环、外来页面和悬空引用不能由策略放行。

首先对完整输入图和全部流执行对象、深度、资源及解码预算检查，再验证专用结构；已验证的回指只在该文档内获得例外。实际标记内容、Form 与图层表达式仍有操作符及取消预算。标签属性保持被动，活动动作和脚本不进入接受范围。此路径不提供 PDF/UA 生成、合规认证或标签阅读顺序重建。

同一源文档的元数据、页面叠加等无关编辑保留原目录对象、图层配置、结构树和引用。`copy()`、`merge()`、`composeObjects()` 的页选择／组合继续拒绝这类文档，包括只选全部页面；它们创建新的页树，尚未定义所有层／标签引用的完整重映射，不能偷偷丢弃文档级结构。

## 语义降级与交付证据

`PDF_STRUCTURE / DEGRADE` 可将受支持渲染、但不能由当前模型表达的整页转为流图片。`PDF_LAYERS / DEGRADE` 额外说明图层成为固定视图；`PDF_TAGS / DISCARD` 说明标签不保留。默认 ERROR，WARN／IGNORE 执行相同处理，IGNORE 仍返回损失。非法引用、坏字体、缺字、权限、签名、预算、取消和 I/O 不能变成降级成功。

详细用法见[语义恢复](pdf-semantic-import-api.md)与 [ADR-0096](adr/0096-pdf-compatibility-paths.md)。可复现语料入口为 `scripts/create_pdf_passive_fixtures.py`、隔离调用方 `pdfPassive` 及 `scripts/verify_pdf_passive.py`；验收结论以实际阶段记录为准，接口文档不代替独立输出证据。
