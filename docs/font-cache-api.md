# 字体缓存与空闲释放

当前开发版默认复用相同内容的字体，缓存有条目数、源字节及空闲期限限制。无后续请求时也会自动移除过期条目；不扫描系统字体，不在用户目录创建字体索引，不修改 FontBox 源码。此功能不在已发布的 `0.1.0-rc.3` 中。

## 默认范围

| 对象 | 默认容量 | 空闲期限与所有权 |
| --- | --- | --- |
| `TrueTypeFontLoader` 加载的不可变 `FontFace` | 16 项、64 MiB 源字节 | 最后一次命中／加载后 5 分钟；过期解除缓存引用，已返回的字体继续可用 |
| PDF 水平 TrueType 解析对象 | 8 项、32 MiB 源字节 | 导出归还后空闲 5 分钟；每个对象由一次导出独占借用 |
| JDK 文字路径的基础物理字体 | 32 项、64 MiB 源字节 | 最后一次使用后空闲 5 分钟；派生字号不进入缓存 |

容量仅约束缓存保存的条目及编码源字节，不是字体解析后总堆、JDK 原生资源或进程 RSS 上限。超过缓存容量的字体仍按原有输入／导出预算处理，本次独立使用后释放，不扩大缓存。

## 加载字体

原有 `TrueTypeFontLoader.load` 重载默认启用共享缓存。缓存按字体内容 SHA-256 匹配；文件每次重新读取并核对内容，不能只凭路径、文件名或修改时间命中。每次调用仍检查 `maxBytes`，调用方数组及可覆写流返回的数组先快照，借用流仍由调用方关闭。非法字体、嵌入许可失败及预算失败不进入缓存。

```java
import com.mirakyux.export4j.font.FontFaceCache;
import java.nio.file.Path;
import java.time.Duration;

try (var fonts = FontFaceCache.builder()
        .maxEntries(8)
        .maxSourceBytes(32L * 1024 * 1024)
        .idleTimeout(Duration.ofMinutes(2))
        .build()) {
    var face = fonts.load(Path.of("fonts/body.ttf"));
    // Use face in FontGroup / DocumentEngine; the cache does not own these callers.
    var stats = fonts.stats();
    fonts.clear();
    // face remains valid after clear or close.
}
```

实例缓存支持 `stats()`、`clear()`、`close()`，关闭后新加载明确失败。`TrueTypeFontLoader.cacheStats()` 和 `clearCache()` 分别观察／清空默认加载缓存；不会关闭调用方持有的字体。需要独立解析对照时使用 `FontFaceCache.builder().enabled(false).build()`。

## 长期服务的字体来源

只让缓存过期不会释放应用单例中的 `FontGroup` 或 `DocumentEngine`。长期服务可保存可重开的 `FontSource`，在同步操作内创建短生命周期引擎：

```java
try (var provider = DocumentEngineProvider.builder(List.of(
        FontSource.file(Path.of("fonts/body.ttf")))).build()) {
    provider.withEngine(engine -> {
        engine.write(document, new PdfBackend(), outputPath);
        return null;
    });
}
```

`DocumentEngineProvider` 位于 `components`，`FontSource` 位于 `font`。来源可选明确文件、类资源或编码字节快照；来源配置本身不持有已解析字体，不扫描目录。字节来源会在配置存活期间保留编码快照。provider 默认拥有有界缓存，`fontCache(cache)` 则借用调用方缓存。`fontRole(name, sources)` 声明独立角色栈，容量配置继续使用原缓存入口。

操作内的字体不会因 `clearCache()` 或关闭而失效；关闭后拒绝新操作。回调若返回布局或自行保存引擎，这些对象仍会持有字体，必须由应用释放。provider 不缓存文档、不强制回收，也不承诺缓存源字节上限等于总堆上限。

## PDF 解析对象

普通 `PdfBackend.write`、`PdfBackend.open` 和 `PdfSpoolBackend` 默认接入同一个有界解析池。一个解析对象在一次导出期间不会交给另一导出并发使用。PDF 字体、COS 对象、Unicode／CID 子集和页级借用状态仍属于各自文档／会话；普通一次性导出不额外启用流式子集快照缓存。

```java
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.pdf.PdfFontCache;
import java.time.Duration;

try (var programs = PdfFontCache.builder()
        .maxEntries(4)
        .maxSourceBytes(32L * 1024 * 1024)
        .idleTimeout(Duration.ofMinutes(2))
        .build()) {
    var backend = PdfBackend.builder().fontCache(programs).build();
    // Reuse backend for independent exports. Each write borrows its own font programs.
    var stats = programs.stats();
    programs.clear();
}
```

后端借用自定义缓存，不负责关闭它。流式入口可使用 `new PdfSpoolBackend(directory, pageLimits, spoolLimits, programs)`。默认池由 `PdfFontCache.defaultStats()`／`clearDefault()` 观察和清空；`enabled(false)` 保留同一输出流程并关闭跨导出驻留。

`clear()`／`close()` 立即移除空闲项，在用项等最后一次借用归还后销毁；不会在输出中途关闭字体。实例关闭后不再接受新借用。失败或取消的导出不会把未确认可复用的解析对象交给后续导出。异步清理失败可通过统计观察，并在后续操作中显式报告。

## 释放与边界

空闲维护任务使用守护线程，仅做缓存清理，不执行文档解析、布局或输出；不继承请求线程的可继承线程本地变量、上下文类加载器或 `ExecutionContext`。空缓存及关闭时停止维护任务。到期清理受 JVM 调度影响，不是实时期限承诺。

移出缓存表示释放该缓存的引用。应用仍持有的 `FontFace`、`FontGroup`、`DocumentEngine`、导出会话及第三方库内部引用依然存活；堆数组何时回收由 JVM 决定。不调用 `System.gc()`，不承诺 TTL 到点时 RSS 立即下降。可选公式模块的固定五款数学字体及上游内部缓存具有独立生命周期，不进入本页的 PDF 解析池。

本次不添加系统字体发现、字体替代、持久化解析对象、字体文件变更监听或全格式字体解析池。静态 CFF、TTC、可变／彩色字体继续遵循各自既有入口和预算，不能从水平 TrueType 复用外推收益。

验证命令与实际结果见[字体缓存验收](verification/font-cache.md)，资源所有权决定见 [ADR-0114](adr/0114-idle-expiring-font-caches.md)。
