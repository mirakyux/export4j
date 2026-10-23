# 单向 PDF 加密与归档

单向源可以使用已有 `PdfOptions` 配置 AES-256 或静态 PDF/A-2b，无须先构造完整语义表格：

```java
var protectedOptions = PdfOptions.storage(PdfOptions.Storage.TEMP_FILE)
        .withEncryption(new PdfEncryption(ownerPassword, readerPassword,
                PdfEncryption.Permissions.READ_ONLY));
var result = engine.export(tableSpecification, sourceFactory)
        .backend(PdfBackend.builder().options(protectedOptions).build())
        .to(targetPath);
```

密码是调用方提供的 `char[]`。读者权限使用与完整文档相同的配置，允许的被动文字／图片链接保持。逐页消费同步完成，最终文件在显式 `finish` 时加密保存；加密不代表 PDFBox 页树恒内存。

静态归档改用显式 RGB ICC 及可嵌入 TrueType 锚点字体：

```java
var archivalOptions = PdfOptions.DEFAULT
        .withArchival(new PdfArchival(rgbIccProfile, anchorFontData));
```

归档与加密互斥。静态归档支持显式 PNG／JPEG、嵌入字体和受支持轮廓；被动链接及普通标准字体命令会失败，不静默移除。所有实际嵌入字体，包括轮廓文字的锚点字体，都占 `PdfLimits.maxFontBytes`；相同字节按 SHA-256 计一次。

`PdfBackend.open`／`PageWriter.accept`／`finish`／`close` 的状态、所有权和线程契约保持：失败会话不能继续，`close` 不保存，借用流不关闭；`DocumentEngine` 路径入口失败保留旧目标并清理临时文件。取消和字节预算作用于整个同步范围。

PDF/A 声明不能替代最终独立合规检查。对结果追加签名、编辑或转换后须重新验证所需标准；签名见[认证 API](pdf-certification-api.md)。完整无障碍、PAdES 和长期信任不由本项提供。

样例任务为 `:examples:streamingProtection`。用 veraPDF 对样例及本次隔离调用方的归档文件生成同一报告，再执行 `python scripts/verify_streaming_protection.py --verapdf-report <REPORT_XML>`。独立证据见[阶段七十](verification/phase-70-streaming-protection.md)，设计见 [ADR-0077](adr/0077-single-pass-pdf-protection.md)。
