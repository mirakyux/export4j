# 阶段七十二：PDF 已有签名的字节完整性校验

新增只读 `PdfSignatureVerifier`，从有界编码快照准备当前签名字段、整数 ByteRange、准确十六进制 Contents、最小 DER 长度及零填充，全部结构和累计预算通过后再调用显式 CMS 校验器。结果将签名字节完整性与当前文件覆盖分开，不把未签追加、字段姓名或认证权限当作信任证据。

新增 8 项协议回归，防止取错签名字节、把无签名／未签追加当作认证、漏掉签名字节篡改、接受小数字节范围／非零填充、绕过预算、提前返回成功、保留借用流及取消后继续调用。单元测试的 DER 摘要夹具只验证取流协议，明确不是密码学 CMS。全仓 1,349 项测试，零失败／错误／跳过；构建、SBOM、本地 bundle 和新隔离调用方通过。

真实离线样例使用显式开发证书及 OpenSSL CMS 回调。三份认证文件和两份追加签名文件含 7 份签名；未签追加保留两份有效历史签名但不覆盖当前末尾；只改变最新批准签名词典中的一个已签元数据字节，第一份签名仍通过、第二份拒绝。共 11 次真实 CMS 校验得到 10 次通过、1 次拒绝。独立 Python／pypdf 重建每份准确 ByteRange、DER、版本末尾和结果，再调用 OpenSSL 对账全部记录。开发证书使用 `-noverify`，没有证明证书链信任。

隔离调用方另验证无签名双 false、完整覆盖但显式拒绝的无效 CMS。两个新增变体与调用方共 3 页，经 Poppler／PDFium 双渲染并逐页目视；原始认证样例在阶段六十八已做页面验收。只读校验前后检查源字节一致，未签追加与单字节篡改各有独立原始字节断言。

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:verifyPdfSignatures --offline --no-daemon `
  '-PsigningOpenSsl=<OPENSSL_EXECUTABLE>' `
  '-PsigningCertificate=<DEVELOPMENT_CERTIFICATE_PEM>' `
  '-PsigningKey=<DEVELOPMENT_PRIVATE_KEY_PEM>'
python scripts/verify_pdf_signatures.py --openssl <OPENSSL_EXECUTABLE>
```

先按[制品指南](../releasing.md)生成本次隔离调用方。工具、证书、私钥和原始日志不进入共享文件。校验器不枚举被后续版本移除的历史字段，不判断 DocMDP 修改符合性、证书身份／信任、撤销、时间戳、PAdES 或长期验证，也不改变普通 `PdfSigner.sign` 的行为。契约见[API](../pdf-signature-verification-api.md)及 [ADR-0079](../adr/0079-explicit-pdf-signature-verification.md)。
