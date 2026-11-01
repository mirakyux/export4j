# 常用文档图片资源

| 文件 | 用途 | 来源与许可 |
| --- | --- | --- |
| `resume_portrait.png` | 简历首页右侧照片，384 × 480 px，显示为 64 × 80 pt | Joon Tae Kim 的 [Pexels 照片](https://www.pexels.com/photo/man-in-white-crew-neck-shirt-8586504/)，经过裁切与缩小；适用 [Pexels License](https://www.pexels.com/license/)，详见[资源说明](resume_portrait-license.txt) |
| `contract_logo.png` | 合同每页页眉的青木示例标记，256 × 256 px，显示为 25 × 25 pt | 项目自绘，Apache-2.0；[矢量源文件](contract_logo.svg)及[许可说明](contract_logo-license.txt) |

照片仅为排版配图，不是虚构履历人物的真实身份，也不代表产品背书。图片随项目打包，示例生成和设计器加载均不访问网络。

固定 PNG 资源的 SHA-256：

```text
contract_logo.png   0ecd7a943a109fed4921bac39e2dcb08fc051ce48452c295e2d5845edb1351f8
resume_portrait.png acc683dc6c9f8b356c6fabea6c28985fa74d8d18e2195e502644c28b4340c64e
```

`CommonDocumentsExample` 将图片及对应说明输出到 `images/` 和 `licenses/`。设计器样例注册相同图片引用，导出完整模板 ZIP 时一并保留。仅导出 XML 时仍须另行提供图片；在设计器的资源面板中可替换同名图片。
