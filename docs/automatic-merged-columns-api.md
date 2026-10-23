# 合并格与有界自动列宽

普通语义 `Table` 允许 `columnSpan`／`rowSpan` 覆盖 `ColumnWidth.Auto(min, max)`，无需手动换成固定列。真实锚点的合并宽度等于覆盖物理列宽之和，左右内边距只扣一次；不生成内部假格。

首选宽度先由单列锚点探测。横跨固定／自动列的锚点按跨度、源行和物理列的稳定顺序施加约束，自动列根据剩余容量按比例增长。页面不足时仍按原规则向显式最小值收缩，固定宽度不变，比例列分享剩余空间。

合并区包含比例列时，使用最终分配的剩余宽度，不把同一个格的首选宽度重复索取给自动列。自动列的 `min` 不是其他比例列的内容下限；若需要保证尾列宽度，应显式使用固定列。某个最终文字框连一个字形也放不下时仍明确失败，不缩小字体。

探测不重复消耗源码点、输出行或缺字诊断额度，最终测量使用实际合并宽度。仅纵向合并的 owner 参与一次普通单列首选宽度探测，保留原始行列与 UTF-16。

本项用于普通文本／富文本／行内图片格；[行内内表 Auto](nested-automatic-tables-api.md)已支持；含块格表与单向源 Auto 仍有各自限制。DOCX 写入标准 `gridSpan`／`vMerge` 和已有自动宽度描述；Word 不承诺采用同一列宽算法。

运行 `:examples:advancedTables` 生成宽／窄 PDF 和标准 DOCX，随后运行 `python scripts/verify_advanced_tables.py`。设计见 [ADR-0074](adr/0074-merged-automatic-columns.md)，独立证据见[阶段六十六](verification/phase-66-merged-automatic-columns.md)。
