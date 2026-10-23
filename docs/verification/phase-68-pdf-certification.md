# 阶段六十八：离线 PDF 认证签名

三档 DocMDP 认证、允许的增量批准签名及签名原子路径已实现。全仓 1,329 项测试通过，零失败／错误／跳过；构建、SBOM、本地 bundle 和全新隔离 Maven 调用方通过。

独立 pypdf 检查 catalog、签名字段与 DocMDP 同一对象、`SigRef`／`TransformParams`、P=1／2／3、catalog Data、完整 ByteRange 及原修订字节前缀。三份认证样本与两份追加签名文件共 7 份 CMS，均使用 OpenSSL 独立验签，修改已签字节全部拒绝。

五份已签文件、源文件及调用方共 7 页由 Poppler／PDFium 双渲染并逐页检查。调用方只通过发布坐标访问新 API，真实回调拒绝后旧目标仍为原值。行为测试覆盖重复认证、已有签名及未知权限拒绝、禁止修改时追加签名拒绝、借用流不关闭、原修订不变、回调异常／取消／字节预算下原子保护、恢复及原子原地签名。

同时补充单向长行在第一页交付失败或取消时只读一个源行的回归，源关闭一次、旧目标和临时文件保持；该项属于阶段六十七后续的失败生命周期覆盖。

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:pdfCertification -PsigningOpenSsl=<OPENSSL_EXECUTABLE> -PsigningCertificate=<DEVELOPMENT_CERTIFICATE> -PsigningKey=<DEVELOPMENT_PRIVATE_KEY> --offline --no-daemon
python scripts/verify_pdf_certification.py --openssl <OPENSSL_EXECUTABLE>
```

先按[制品指南](../releasing.md)生成本次全新调用方，使用固定 Python 验证依赖。签名样本使用本地开发自签证书，不随仓库分发。OpenSSL 检查使用 `-noverify`，证明内容签名完整性及篡改拒绝，不验证证书信任；阅读器权限执行、原有签名验证、PAdES／时间戳／撤销资料和长期验证未由本批完成。契约见[认证 API](../pdf-certification-api.md)及 [ADR-0076](../adr/0076-offline-pdf-certification.md)。
