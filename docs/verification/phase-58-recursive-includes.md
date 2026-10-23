# 阶段五十八：递归片段与动态目的地

## 行为

已登记片段可以继续 include；父子 prefix 组合、父到子静态目标及 Each 副本引用保留。循环拒绝，活动链最多 16 层；最终树深度、展开元素、预收集目标及绑定分别受预算和取消控制。

动态 href 按当前词法数据范围为文字与图片解析，最终协议、主机、控制字符和凭据再次校验。非法地址以脱敏 INVALID_VALUE 失败。动态字段目标使用调用方提供的完整最终 ID；静态目标继续自动重定位。

## 可复现命令

```powershell
.\gradlew.bat build sbom publishLocalBundle :examples:recursiveIncludes :examples:xmlIncludes :examples:xmlReferences :examples:xmlTemplates
python scripts/verify_recursive_includes.py
python scripts/verify_xml_includes.py
python scripts/verify_xml_references.py
python scripts/verify_xml_templates.py
```

隔离 Maven 调用方使用本次本地 bundle，成功目录由既有指针记录；参见[发布验证](../releasing.md)。Python 依赖版本由 `scripts/requirements-verification.txt` 固定。

## 输出验收

`examples/build/recursive-includes` 生成三页 PDF 与 DOCX。独立脚本检查两次循环副本的父／子引用、编号、主模板外部目标、动态完整目标、最终 PAGE／PAGES、图文外链及图像次数；标准 OOXML 检查组合后的书签 owner、PAGEREF／REF、分页和外链关系。最终制品不能包含未绑定表达式或原型 URI。隔离调用方另外生成一页 PDF 和 DOCX，使用发布 JAR 的公共入口。

新 PDF 页使用 Poppler／PDFium 双渲染，并逐页查看。DOCX 这里只验证标准结构和目的地关系，不代表 Word 保存回读或相同分页。动态地址不自动编码，递归复用仍不支持行内／格内 include、动态 ref／prefix 或相对父级路径。
