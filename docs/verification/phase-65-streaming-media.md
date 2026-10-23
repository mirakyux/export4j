# 阶段六十五：单向长表图像及外链

## 结果

固定列单向长表表头／正文支持显式 PNG／JPEG、图文混排及被动文字／图片外链，兼容横向合并。最终链接几何与完整文档共享；逐页消费方和后端未接入交互时明确失败，不静默丢弃。

全仓 1,320 项测试通过，零失败／错误／跳过。构建、SBOM、本地 bundle 和全新隔离 Maven 调用方通过。新增示例 3 页、调用方 1 页，共 4 页经 Poppler／PDFium 双渲染并逐页检查，可见笔画未匹配比例为零。未刷新视觉基线。

## 可复现检查

```powershell
.\gradlew.bat -g build/gradle-validation build sbom publishLocalBundle :examples:streamingMedia :examples:streamingTable :examples:streamingRichText :examples:streamingColumnSpans :examples:inlineImages :examples:production :examples:capabilities --offline --no-daemon
python scripts/verify_streaming_media.py
python scripts/verify_streaming_table.py
python scripts/verify_streaming_rich_text.py
python scripts/verify_streaming_column_spans.py
python scripts/verify_inline_images.py
python scripts/verify_production.py
```

Python 使用项目固定验证依赖，Poppler 在 PATH 中。先按[制品指南](../releasing.md)从本次 bundle 创建全新隔离调用方，再读取其成功目录指针。离线运行使用既有缓存依赖。

## 证据及边界

独立检查最终 PDF 的 17 个表格边框、10 次图片呈现、PNG／JPEG 解码像素、逐页 8／8／4 个 URI 注释及调用方 2 个注释。合并表头重复，正文 ROW-01～07 唯一有序；图片框、页面边界、一次内边距及关闭一次分别检查。

行为测试覆盖图像原文位置、物理列、表头测量复用、单行预读、重复表头链接预算、旧消费方失败、PDF 累计交互数量／URI 预算、非法页号／越界及失败终止。原普通／富文本单向、横向合并、完整图文和生产输出独立回归通过。

这组有限样例不证明持续容量或任意阅读器兼容；PDFBox 仍保留累计页树及注释。字段、纵向合并、拆行、块格及未来语义继续独立。契约见[图文 API](../streaming-media-api.md)和[ADR-0073](../adr/0073-single-pass-images-and-links.md)。
