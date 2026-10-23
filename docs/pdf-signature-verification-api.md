# PDF 签名字节完整性校验 API

`PdfSignatureVerifier` 从一个有界 PDF 快照中读取当前表单树的签名字段，严格准备每份签名的 ByteRange 和 DER CMS，再调用显式的离线 `CmsVerifier`。结果分别报告签名字节完整性及当前文件是否被某份签名完整覆盖；证书身份、信任链、撤销、时间戳、DocMDP 修改许可和 PAdES 需要独立校验。

```java
public static PdfSignatureVerifier.Report verifyCurrentFile(
        Path source, PdfSignatureVerifier.CmsVerifier verifier) throws IOException {
    var report = PdfSignatureVerifier.verify(source,
            PdfSignatureVerifier.Limits.DEFAULT, verifier);
    if (!report.allSignedBytesValid() || !report.currentFileCovered()) {
        throw new IOException("PDF signature integrity or current-file coverage failed");
    }
    return report;
}
```

调用方显式提供真实 CMS 实现。回调收到借用的准确签名字节流及独立 DER 数组，须完成密码学验签并消费全部签名字节后才能返回 `true`；返回 `false` 表示完整性校验不通过，I/O／工具异常继续抛出。回调返回后字节流失效，不可异步保留。核心不加载私钥、系统信任库或网络，也不内置命令行工具。

## 结果含义

`Report.signatures()` 按已签版本的字节长度递增排序，每项包含该版本字节长度、Contents 起止偏移、实际 DER 字节数、`coversCurrentFile` 和回调的 `integrityValid`。

| 情形 | `allSignedBytesValid()` | `currentFileCovered()` | 含义 |
| --- | --- | --- | --- |
| 无签名字段 | false | false | 无完整性证据 |
| 全部回调通过，末份覆盖完整文件 | true | true | 对应准确字节通过调用方的校验策略 |
| 旧签名通过，之后追加未签字节 | true | false | 历史签名仍有效，当前文件存在未覆盖部分 |
| 覆盖完整文件但验签失败 | false | true | 范围完整不代表完整性通过 |

该入口检查当前可达的签名字段，不宣称枚举被后续版本移除的全部历史签名。先前签名通过也不证明后续变更满足认证权限；签名词典中的姓名等数据不作为已认证身份返回。`PdfSigner.sign` 保持原有显式行为，不会因此自动验证已有签名或证书信任。

## 严格边界及预算

- 支持未加密输入中的 `adbe.pkcs7.detached`／`ETSI.CAdES.detached`；支持 CAdES 容器不代表 PAdES 合规。
- ByteRange 恰有四个非负整数，第一段从零开始；唯一间隙必须精确对应同一 Contents 的十六进制字符串，不能排除其他文件数据。
- DER 必须是有限、完整、最小长度编码的序列；预留区只允许零填充。字面字符串 Contents、非零填充、同版本多签、畸形范围和未知容器明确失败。
- 默认输入 32 MiB、至多 64 份签名、每份含预留填充的 Contents 至多 1 MiB、累计签名字节 128 MiB；累计额度对各历史版本分别计数。`Limits` 可显式缩小或扩大有限额度。
- 表单遍历限制 10,000 个节点及 10,000 个排队节点，拒绝环、重复字段及重复签名值。先完成全部结构和预算检查，再调用任何校验器。
- 借用输入资源不泄漏，库只读取源文件；观察当前同步执行范围的取消／超时。回调是可信、合作式代码，PDF 解析器不是恶意输入沙箱。

真实离线示例执行 OpenSSL CMS 验签，明确使用 `-noverify`，仅证明完整性。测试证书及私钥由调用方在忽略的验证目录显式提供；示例不捆绑密钥。运行 `:examples:verifyPdfSignatures` 会先生成认证样例；通过 `signingOpenSsl`、`signingCertificate`、`signingKey` 配置工具和材料。结构及独立完整性证据见[阶段七十二](verification/phase-72-pdf-signature-verification.md)，设计见 [ADR-0079](adr/0079-explicit-pdf-signature-verification.md)。
