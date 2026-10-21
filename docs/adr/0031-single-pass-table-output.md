# ADR-0031：单向长表与同步逐页输出

- 日期：2026-10-16
- 状态：已采纳；验收结果单独记录

## 背景

普通 Table 保存所有源行，DocumentLayouter 缓存全量测量，DocumentLayout 与 DisplayDocument 再保存全部页。TableCursor 仅分配已测量表格；PDFBox TEMP_FILE 仅改变流存储。两者均不能提供单次读取的长表导出。

## 决策

模型增加独立 StreamingTable 固定列规格与有限表头，不实现 Block、不保存正文。TableRowSource.Factory 在导出时创建源，next 返回 Optional<TableRow>，源是同步可信调用方代码。库关闭工厂创建的源；核心布局只借用源。

StreamingTableLayouter 每行测量一次，仅保存表头、当前页和一个待放置行。初版限普通文字格、固定 point 列宽和整行分页；表头重复，空源恰好一页，首行无法容纳时不先输出表头空页。源行列／码点与重复呈现的输出行、诊断分别计费，诊断保留物理页、源行列和格内 UTF-16。

DocumentPainter.paintPage 从单页 IR 生成单页 Display List，不重新布局。graphics-api 定义 PageBackend／PageWriter，同步接收并释放传入页，显式 finish 成功，close 仅清理且幂等。不存在默认的全页收集器。PdfBackend 复用原渲染和累计预算，每页即时转为 PDFBox 对象，仍保留 PDDocument 页树；不宣称完整 PDF 恒定内存。

DocumentEngine.export(spec, factory) 提供链式 backend／limits／diagnostics／options／to。先开后端再开行源，关闭行源后才 finish，写出器关闭失败仍使操作失败。返回扁平计数，不返回完整 IR 或诊断列表。路径写入使用同目录临时文件和原子替换；失败保留原文件，清理失败附加为 suppressed。借用输出流不关闭。

Progress.total=-1 表示未知总量，hasKnownTotal() 用于显示判断，不能伪造百分比。同步检查点覆盖源调用前后、测量、诊断及页交付；阻塞的调用方代码只能协作取消。

## 边界与替代方案

初版明确拒绝富文本、图片、字段及配置的页模板／导航／注释／字体角色，PDF 会话拒绝加密与归档。无自动列宽、合并、拆行、嵌套、总页数、目录、未来引用、脚注、平衡、DOCX 或签名支持。需要未来信息的二遍布局必须另有稳定快照或显式磁盘 spool，不能重新查询数据库并假定一致。

未采用 Iterator 包装现有 Table，因为完整模型和测量仍累积；未直接在布局里操作 PDFBox，因为会破坏后端替换及职责边界；未让 close 隐式完成输出，因为失败清理可能把部分结果误认成功。公开逐页 SPI 可供其他后端显式实现，但现有 SVG／Raster 的完整文档入口保持。

## 验证要求

行为验收覆盖单次消费、最多一行预读、顺序／表头／来源、所有预算、超高失败、取消／超时、回调失败、关闭顺序、suppressed 和目标保持。小样须独立解析、双渲染、逐页复查及隔离坐标验证。容量须分别测量独立 JVM 的布局丢弃和真实 PDF，记录 Windows 工作集／私有字节／句柄与 JVM 堆；不能把布局结果推断为 PDF 常量内存。
