# 外部 PDF 的嵌入字体兼容

页面渲染、内容流文字替换、光栅脱敏及语义恢复共用嵌入字体预检。有效静态 CFF 字体不再因为不是 TrueType 而被整页拒绝；仍不扫描系统字体，也不创建隐式替代字体。

## 接受的字体程序

| PDF 字体声明 | 嵌入位置及程序 |
| --- | --- |
| TrueType | FontDescriptor／FontFile2：静态 TrueType |
| Type0／CIDFontType2 | 后代 FontDescriptor／FontFile2：静态 TrueType |
| Type1 | FontDescriptor／FontFile3，Subtype 为 Type1C：单一名称索引的 CFF1 程序 |
| Type0／CIDFontType0 | 后代 FontDescriptor／FontFile3，Subtype 为 CIDFontType0C：单一 CID 索引的 CFF1 程序 |
| 上述两类 CFF 字体的 OpenType 容器 | FontFile3 的 Subtype 为 OpenType：静态 OTF／CFF，程序种类须与 PDF 字体声明一致 |

检查覆盖全部资源，包括未使用字体、Form、当前批注外观、ExtGState 字体与软蒙版组。先对输入流执行解码额度预检，再解析确切字体程序；字体种类、容器及嵌入字段不得冲突。同一程序流在一次资源检查内复用验证，不能用不同字体声明绕过检查。

未嵌入字体、传统 Type1／PFB、Type3、可变字体和 CFF2 不进入此子集。源内没有程序时，按对应入口的资源损失规则拒绝或跳过；程序损坏、声明矛盾和实际缺字须失败，WARN／IGNORE 不绕过这些检查。字体解析和绘制受现有字节、文字及操作符额度控制，第三方解析器不提供硬内存沙箱。

## 各入口的结果

| 入口 | CFF 输入的实际处理 |
| --- | --- |
| [页面渲染](pdf-raster-api.md) | 使用嵌入的原字形轮廓生成 PNG／JPEG；实际绘制字形还检查嵌入状态、损坏状态及字形存在性 |
| [文字替换](pdf-text-editing-api.md) | 从原编码及 Unicode 映射匹配；未命中内容保留 CFF 字体，命中内容仍使用调用方显式 TrueType 字体并返回字体损失 |
| [光栅脱敏](pdf-redaction-api.md) | 渲染嵌入 CFF 后清除指定像素，新文件不携带源字体、文字对象或历史 |
| [语义恢复](pdf-semantic-import-api.md) | 恢复具有合法 Unicode、实际字形及受支持水平几何的可见文字；目标字体通过显式角色映射和导出配置选择，不直接把源字体程序放入语义模型 |

字体兼容不会扩大 Form 编辑、复杂文字状态、图层、标签结构或页面几何子集，也不将轮廓路径或未经验证的 ActualText 还原为正文。新生成 PDF 的可见 CFF 正文仍采用既有轮廓输出，不能把本项误认为新增原生 CFF 嵌入后端。

实际 Type1C、OTF／CFF、CIDFontType0C 输入、跨显示指令替换及四格式转换见[阶段七十六](verification/phase-76-pdf-font-compatibility.md)。
