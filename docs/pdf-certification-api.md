# PDF 认证签名

```java
var options = new PdfSigner.Options("签名人", "文档认证", signingInstant, 16_384);
PdfSigner.certify(sourcePath, targetPath, options,
        PdfSigner.CertificationPermission.FORM_FILLING_AND_SIGNING,
        content -> signer.createDetachedCms(content));
```

`content` 是借用的准确 PDF ByteRange 流，生命周期截至回调返回。回调返回完整 DER detached CMS，不能返回裸摘要或 RSA 签名。签名时间显式传入，库不内置私钥、证书或网络调用。

| 权限 | DocMDP P 值 | 声明允许的后续变化 |
| --- | --- | --- |
| `NO_CHANGES` | 1 | 不允许修改 |
| `FORM_FILLING_AND_SIGNING` | 2 | 表单填写及追加签名 |
| `ANNOTATIONS_FORM_FILLING_AND_SIGNING` | 3 | 上述变化及注释 |

认证签名必须是第一份签名。已有签名、已有 catalog 权限或加密源输入会失败。第二／三档可调用既有 `PdfSigner.sign` 追加批准签名；第一档、畸形及未知 catalog 权限明确拒绝。该追加入口不会自动验证已有签名或已有修订是否符合权限；可独立调用[字节完整性校验器](pdf-signature-verification-api.md)，其 CMS 回调与当前末尾覆盖报告不代替修改许可／信任判断。

`Path` 目标入口使用同目录临时文件和原子替换，要求父目录存在且文件系统支持原子移动；不降级为复制。源与目标可相同。回调异常、CMS 为空／超额、I/O、取消及输出预算失败保留旧目标并清理临时文件。`OutputStream` 重载借用流、不关闭，失败可能留下部分字节，须丢弃输出。

可用 `ExecutionContext.execute(options, operation)` 在整个操作外包同步取消、超时及输出字节预算；外部签名器应自行设置有界超时。库只能在回调返回或主动检查时观察取消，不能强制抢占任意回调。

DocMDP 声明由支持它的验证器解释，不阻止应用修改字节。当前使用 `adbe.pkcs7.detached`；认证结构及 CMS 完整性验签不证明证书受信任、阅读器权限执行、PAdES、可信时间戳或长期验证。

样例 `:examples:pdfCertification` 显式接收 `signingOpenSsl`、`signingCertificate`、`signingKey` 三个 Gradle 属性，不生成或捆绑密钥。独立验证运行 `python scripts/verify_pdf_certification.py --openssl <OPENSSL_EXECUTABLE>`，证据见[阶段六十八](verification/phase-68-pdf-certification.md)。
