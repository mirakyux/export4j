# ADR-0079：显式 CMS 校验与 PDF 字节覆盖分离

状态：已采纳。

## 决策

PDF 后端提供只读 `PdfSignatureVerifier`，与现有外部 `CmsSigner` 对称，密码学校验由显式回调提供。核心负责有界编码快照、当前字段树、整数 ByteRange、唯一 Contents 间隙、DER 长度／零填充及借用流生命周期。全部签名先准备和核对预算，再逐一执行回调，失败不返回部分成功报告。

`integrityValid` 只表示回调对准确签名字节的结果，`coversCurrentFile` 只表示该范围达到当前文件末尾。两个聚合方法分开；无签名时都为 false。否则追加未签数据仍可能保留有效历史签名，而完整覆盖的篡改输入仍可能验签失败。字段姓名、认证词典或普通 PDF 解析成功均不作为信任证据。

回调必须在返回 true 前消费完整字节流，流在回调后关闭失效；任何回调异常／取消中止操作。默认不增加密码学依赖，不接入系统信任、远程验证或隐式子进程。可运行示例显式使用离线 OpenSSL，真实完整性、未签追加和签名字节篡改分别验收。

## 边界

当前表单树不是全部历史版本的签名取证。DocMDP 修改符合性、证书链、算法策略、撤销、时间戳、PAdES 和长期验证独立；CAdES SubFilter 的容器支持不能冒充标准合规。`PdfSigner` 的普通追加行为及已有契约保持，不把一次验证结果自动用于之后可能变化的源文件。

契约见[API](../pdf-signature-verification-api.md)，验收见[阶段七十二](../verification/phase-72-pdf-signature-verification.md)。
