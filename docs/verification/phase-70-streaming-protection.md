# 阶段七十：单向 PDF 加密及静态归档

逐页后端支持已有 AES-256 权限和静态 PDF/A-2b 选项，单向图文及被动链接可以加密输出；静态归档继续拒绝链接和标准字体。全仓 1,333 项测试，零失败／错误／跳过；构建、SBOM、本地 bundle 和全新隔离调用方通过。

样例加密／归档各 2 页，调用方各 1 页，共 6 页经 Poppler／PDFium 双渲染并逐页检查。独立检查错误密码拒绝、读者／owner 凭证、AES-256 参数、只读权限位、逐页源顺序、14 次图片呈现和 7 个被动链接。归档文件及调用方由 veraPDF 1.30.3 按 PDF/A-2b 验证，各通过 144 条规则，分别完成 10,032／628 项检查，失败项为零。

首轮检查暴露验证器对 pypdf 普通提取的假设：混合图文使用轮廓文字及 ActualText，pypdf 普通提取只返回非绘制锚点。最终检查保留严格原文断言，分别验证 PDFium 实际提取与原始 ActualText 完整序列，再核对全部图像及独立双渲染。没有把锚点提取当作逻辑文字，也未放宽内容阈值。

源码复核还发现完整文档及逐页路径的 PDF/A 轮廓锚点字体未占 `maxFontBytes`。两条路径现共用字体计费，显式 SHA-256 去重。回归验证不足一字节即失败、失败不保存及会话终止；锚点与普通字形共享同一字体时只计一次。其他行为覆盖借用输出、元数据、权限、交互拒绝及显式完成。

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:streamingProtection --offline --no-daemon
verapdf --format xml --flavour 2b examples/build/streaming-protection/archive.pdf <CURRENT_CONSUMER_OUTPUT>/consumer-streaming-archive.pdf > build/verification/streaming-protection-verapdf.xml
python scripts/verify_streaming_protection.py --verapdf-report build/verification/streaming-protection-verapdf.xml
```

先按[制品指南](../releasing.md)生成本次隔离调用方，用其成功目录替换占位符；Windows 可使用 `verapdf.bat`。固定 Python 验证依赖及 Poppler 在验证环境可用。普通完整文档的加密、归档、外部 CMS 样例亦重新生成，经独立验签／篡改拒绝和 veraPDF 回归通过。

不提供 PDF/UA、PAdES、自动签名、任意归档交互或 PDF 恒内存。签名后如有标准目标，需验证最终结果。契约见[安全输出 API](../streaming-protection-api.md)及 [ADR-0077](../adr/0077-single-pass-pdf-protection.md)。
