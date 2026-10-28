# PDF 标准、无障碍与离线签名

Export4j 的标准输出分别选择 PDF/UA-1、PDF/A-1b／2b／3b／4f 和 PAdES B／T／LT／LTA。它们有不同的输入条件和验收要求；配置中的标准标识不能代替最终文件的独立合规检查。验收证据见[阶段九十六](verification/phase-96-pdf-standards.md)。

## PDF/UA-1 无障碍输出

PDF/UA 输出消费明确的逻辑阅读顺序。`ActualText`、可选中文字和坐标排序都不能代替结构树。

已有语义文档可以先布局，再使用源模型建立标签：

```java
var result = engine.layout(document);
var display = DocumentPainter.paintAccessible(document, result.body(), "zh-CN");
var options = PdfOptions.DEFAULT.withAccessibility(PdfAccessibility.PDF_UA_1);
try (var output = Files.newOutputStream(Path.of("output/accessible.pdf"))) {
    new PdfBackend(PdfLimits.DEFAULT, options).write(display, output);
}
```

`engine` 为显式字体配置的 `DocumentEngine`，`document` 为对应的源 `Document`。源模型与布局必须一致。当前自动映射包括标题、普通段落、简单项目列表、独立行内图像、文本单元格表格及其续页。列表标记与正文分别映射 `Lbl`／`LBody`；图像使用 `InlineImage` 的替代文本；物理表头拥有稳定 ID，数据格关联对应表头，重复绘制的续页表头作为 artifact，不重复逻辑内容。

块单元格、混排图像、自定义组件及其他复杂来源需要调用方提供明确绑定，自动映射入口会拒绝。原有普通 `paint`／导出入口继续生成普通 PDF，不推断无障碍语义。

直接使用 Display List 时，第四个 `DisplayDocument` 参数为后端中立的 `AccessibilityTree`。每个 `Node` 声明角色、稳定 ID、可选语言、图像替代文本、表格属性和有序子节点；`CommandRange` 以从 0 开始的页／指令索引指定半开区间。非语义装饰必须加入 `artifacts`。模型检查以下条件：

- 所有实际绘制均属于一个逻辑节点或 artifact，绑定不能重叠或超出页面。
- 标题级别不跳跃，列表具有 `L → LI → Lbl/LBody` 层次。
- 图像具有非空替代文本；表头声明 `Scope`，数据格的 `headers` 指向实际 `TH` ID。
- 节点、深度及文本有固定上限，不通过坐标猜测语义。

PDF 后端生成 `StructTreeRoot`、`ParentTree`、`IDTree`、页面 MCID、表格属性、文档语言、标题显示偏好及 PDF/UA-1 XMP 标识。要求非空文档标题及显式嵌入字体／带逻辑文本的轮廓文字；拒绝未嵌入的标准字体。当前不生成带 OBJR 标签的交互对象，非空交互 extras 会失败。页面流式会话没有完整逻辑树，因此不能用于 PDF/UA 输出。

必须同时提供语义树和 `PDF_UA_1` 配置。遗漏其中一项会报错；SVG／PNG 后端也会拒绝非空语义树，避免静默丢失。

### 已有标签文件

```java
var limits = PdfTaggedDocument.Limits.DEFAULT;
var original = PdfTaggedDocument.inspect(Path.of("input/tagged.pdf"), limits);
var revised = PdfTaggedDocument.metadata(
        Path.of("input/tagged.pdf"), Path.of("output/tagged.pdf"),
        "修订后的文档标题", "zh-CN", limits);
```

`inspect` 返回实际角色、替代文本、表头 ID 和页／MCID 来源，核对结构父子关系、ParentTree、唯一所有者和绘制覆盖。`metadata` 仅修改标题及文档默认语言，保留页面内容、资源及逻辑结构；目标以原子移动替换。它拒绝签名、加密、交互对象、OBJR 和结构拥有的 Form 内容，不修复或重新标记任意外部 PDF。

结构检查和 veraPDF 属于自动验收，辅助阅读器的标题跳转、表格导航、替代文本和阅读顺序属于单独证据。具体样例的已执行与未执行步骤见[阶段九十六](verification/phase-96-pdf-standards.md#无障碍阅读器)。

## PDF/A 归档输出

`PdfArchival` 要求调用方提供 RGB ICC 配置字节和允许嵌入的 TrueType 锚点字体；不发现系统字体、读取远程资源或自动替换缺字。

| 配置 | 版本与等级 | 色彩和字体 | 附件与交互 |
| --- | --- | --- | --- |
| `A1B` | PDF/A-1b，PDF 1.4 | RGB、ICC v2；字体嵌入；拒绝透明图片 | 不接受附件或交互 extras；不用对象流／交叉引用流 |
| `A2B` | PDF/A-2b，PDF 1.7 | RGB；字体嵌入；允许图片 alpha | 不接受附件或交互 extras |
| `A3B` | PDF/A-3b，PDF 1.7 | RGB；字体嵌入；允许图片 alpha | 可提供显式关联文件；拒绝交互 extras |
| `A4F` | PDF/A-4f，PDF 2.0 | RGB；字体嵌入；XMP 声明 2020 修订 | 至少一个关联文件；拒绝交互 extras；不写旧式 Info 字典 |

保留的双参数 `new PdfArchival(iccBytes, anchorFont)` 默认选择 `A2B`。此 API 没有选择 PDF/A 的 A／U 等级、PDF/A-4e 或 PDF/UA 与 PDF/A 的组合认证，不能由已有等级推断这些能力。

```java
var archive = new PdfArchival(PdfArchival.Profile.A2B, rgbIccBytes, anchorFont);
var options = PdfOptions.DEFAULT.withArchival(archive);
try (var output = Files.newOutputStream(Path.of("output/archive.pdf"))) {
    new PdfBackend(PdfLimits.DEFAULT, options).write(display, output);
}
```

`AssociatedFile` 明确提供文件名、MIME 类型、说明、`AFRelationship`、修改时间和字节。文件名不能是路径；单个文件最多 16 MiB，合计最多 32 MiB，数量最多 100。构造和访问均复制字节。后端建立 EmbeddedFiles 名称树及 AF 关联，不从 URI 读取内容。加密与归档配置不能并用。

### 已有 PDF 转换

`PdfArchivalConverter` 将受支持的现有静态页面重建为不透明 RGB 图片，再写入指定归档版本。它不承诺保留可编辑正文或矢量结构：全文搜索、矢量编辑、标签、交互、源附件及源元数据均丢失，清晰度受显式 DPI 限制。新文档元数据和附件由归档配置另行指定。

```java
var policy = ProcessingPolicy.builder()
        .feature("PDF_ARCHIVAL_RASTERIZATION",
                ProcessingPolicy.Action.WARN, ProcessingPolicy.Treatment.DEGRADE)
        .build();
var options = PdfArchivalConverter.Options.defaults(archive, metadata);
var report = PdfArchivalConverter.convert(
        Path.of("input/source.pdf"), Path.of("output/archive.pdf"), options, policy);
```

默认 `STRICT` 拒绝该损失。WARN／IGNORE 使用同一重建方式，IGNORE 仍返回实际损失；只接受 `DEGRADE`，不能用 `PRESERVE` 假装保留结构。现有转换子集接收有效嵌入的 TrueType／Type0 字体、普通实色绘制、不透明 8 位 RGB／Gray 图片和受限普通 Form；拒绝签名、加密、表单、图层、活动动作、Type3、图案、渐变、透明组及不支持的源图片。源字体不完整时直接失败，不使用系统字体补齐。

输入字节、页数、源／目标像素、解码字节、操作符、状态深度和输出字节均有上限。路径输出在转换、关闭及取消检查成功后原子替换旧目标。每份最终文件仍需使用独立验证器检查，不能因写入了 `pdfaid` 元数据就判断合规。

## PAdES 与离线信任

`PdfSigner`／`PdfSignatureVerifier` 原有接口继续分别提供外部 CMS 签名和确切字节完整性验证。新的 `PdfPades` 负责标准等级、显式信任、撤销和可信时间，不将“CMS 字节完整”视为“签名者可信”。

| 等级 | 实际内容 |
| --- | --- |
| B | `ETSI.CAdES.detached`、SHA-256 及 `SigningCertificateV2` 证书绑定 |
| T | 在 B 上加入覆盖签名值的可信 RFC3161 时间戳 |
| LT | 在 T 上加入 DSS／VRI、证书链及 OCSP／CRL，验证相应链的撤销状态 |
| LTA | 在 LT 上加入覆盖确切 PDF ByteRange 的文档时间戳，可继续追加资料和时间戳 |

应用显式提供 JDK `PrivateKey`、DER 证书链、信任锚、验证时刻、证据有效期策略及时间戳回调：

```java
var identity = new PdfPades.Identity(signingKey, List.of(signerDer, issuerDer));
var trust = new PdfTrust.Material(List.of(rootDer), List.of(issuerDer),
        validationTime, Duration.ofDays(7), Duration.ofMinutes(1));
var evidence = new PdfTrust.Evidence(
        List.of(signerDer, issuerDer, tsaDer), ocspResponses, crls);
var options = PdfPades.Options.defaults(
        PdfPades.Level.LTA, "Document signer", "Approval", signingTime);
var report = PdfPades.sign(sourcePath, targetPath,
        options, identity, trust, evidence, timestampSource);
```

示例中的密钥、DER 字节、时刻、路径和 `TimestampSource` 均由应用持有；库不编码私钥。回调接收 SHA-256 摘要，返回一份 RFC3161 **TimeStampToken** DER，不是 HTTP 响应或 TimeStampResp。库在使用前验证摘要、签名、TSA 证书专用 EKU、链和时间；错误摘要及超前于显式验证时刻的 token 会失败。

当前内容签名使用 RSA／ECDSA 和 SHA-256；RSA 公钥至少 2048 位，EC 至少 256 位。证书和撤销证据使用受支持的 SHA-256／384／512 RSA 或 ECDSA 签名。算法不受支持会失败，不下调安全要求。

信任链仅使用显式信任锚、中间证书、CMS 证书和证据证书，不读取系统信任库，也不通过 AIA、OCSP／CRL 地址或 TSA 地址联网。PKIX 检查证书有效期、约束和签名。OCSP 校验签名、证书 ID、授权响应者、状态和时间；CRL 支持完整直接颁发者列表，不支持 delta／间接 CRL。证据必须声明 `nextUpdate` 并覆盖对应验证时刻。有效的撤销证据优先于冲突的 GOOD 状态。

没有可信时间戳的 B 使用调用方的验证时刻。T／LT／LTA 使用通过验证的时间戳时刻检查历史证书和撤销覆盖；签名字典 `/M` 及 CMS signingTime 自身不能证明可信时间。“证据过期”指证据不覆盖相应时刻或超过显式年龄预算，不能简单以当前日期否定已被长期时间戳保护的历史证据。

### 验证、续期与认证权限

```java
var verified = PdfPades.verify(sourcePath,
        PdfSignatureVerifier.Limits.DEFAULT, trust, evidence);
boolean longTermValid = verified.meets(PdfPades.Level.LT);

var renewalOptions = PdfPades.Options.defaults(
        PdfPades.Level.LTA, "Document TSA", "Evidence renewal", renewalTime);
var renewed = PdfPades.extend(sourcePath, renewedPath,
        renewalOptions, renewalTrust, renewedEvidence, timestampSource);
```

`Signature` 分别报告字节完整性、信任、实际达到的等级、可信时间和内容无关的失败原因。没有长期资料不会自动提升等级；未知／撤销状态、错误链、失效时间戳和篡改不会被接受。`extend` 要求已有有效可信签名时间戳，不能给裸 B 签名补造历史时间。续期保留已有 DER 流和 VRI 引用，只追加证据与新时间戳。

`certify` 在首次签名时接受 `PdfSigner.CertificationPermission`，分别声明 P1 禁止普通修改、P2 允许填写／签名、P3 增加批注修改。文档时间戳没有外观，DSS／时间戳更新与普通表单外观修改分别处理。

`PdfDocMdp.verify` 比较认证时修订与当前有效内容：页面及继承资源、目录／元数据、字段定义、值／外观、widget、批注和证据更新。原有默认字体资源不能被删改；P2／P3 填写所需的附加字体单独分类。结果为 `NOT_CERTIFIED`、`PERMITTED`、`FORBIDDEN` 或 `INDETERMINATE`。未知权限结构或无法识别的追加不算允许；这是修改符合性，不能替代加密验签和信任。阅读器是否禁止某个编辑动作由阅读器自己执行，认证声明不能阻止任意应用改写字节。

PAdES 初始签名拒绝已有签名、加密及活动内容；续期资料路径单独验证已有签名。现有普通 PDF 编辑入口继续在预检识别签名影响，没有经过验证的保留路径便拒绝，不能由损失策略绕过。路径签名和续期均有有限输入／输出／证据预算、同步取消及失败旧目标保护；签名库不构成恶意 PDF 解析沙箱。
