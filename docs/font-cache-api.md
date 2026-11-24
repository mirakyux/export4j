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

直接使用 `export4j-font-core` 加载 TrueType 或 CFF 文件时，需添加同版本的 `export4j-font-fontbox` 作为运行依赖。PDF 后端、组件和矢量公式模块会传递此解析实现。

原有 `TrueTypeFontLoader.load` 重载默认启用共享缓存。缓存按字体内容 SHA-256 匹配；文件每次重新读取并核对内容，不能只凭路径、文件名或修改时间命中。每次调用仍检查 `maxBytes`，调用方数组及可覆写流返回的数组先快照，借用流仍由调用方关闭。非法字体、嵌入许可失败及预算失败不进入缓存。

`FontSource.bytes(...)` 在创建时用内部 `SnapshotFontSource` 保存私有编码快照及其 SHA-256 标识，后续加载复用这份不可变内容身份；调用方不能分别提供字节和摘要。公开 `FontFaceCache.load(byte[])` 仍每次保护外部数组，不按调用方对象身份信任内容。文件和类资源来源继续按次读取，不能用该快照路径掩盖文件变化。

输入读取、快照摘要和冷解析在缓存锁外执行，锁只协调命中、容量、失效与结果登记。有界的同键待加载项可共享正在解析的结果；协调条目及源字节另受缓存配置限制，超过协调容量的调用仍按原预算独立解析，不转成无界排队或拒绝有效字体。该机制不等于限制整个应用所有并发解析的总内存。

需要在同键等待期间观察取消／截止时间时，使用 `FontFaceCache.load(source, checkpoint)` 或带字节限额的 `load(source, maxBytes, checkpoint)`；`source` 可为 byte 数组、借用流或明确 Path。回调在调用线程及缓存锁外同步执行，等待期间每 25 ms 重查一次，实际响应仍受 JVM 调度影响；异常只终止当前等待者，不取消共享解析或其他使用者。调用方 I/O 与字体解析仍是不可抢占的同步阶段。

`FontSource.load(cache, checkpoint)` 为兼容默认方法，原函数接口与 lambda 保持有效。内建 bytes／file／resource 来源把检查点传入缓存；自定义来源默认仅在其调用前后检查，需要在内部等待期间合作取消时，须覆盖新重载并传递检查点。starter 和 `DocumentEngineProvider` 均传入 `ExecutionContext::checkpoint`，不向字体模块引入执行模型依赖或全局上下文。

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

`clear()` 移除已驻留字体并退休当时已登记的解析任务：任务可继续为已有调用方返回结果，但不再把该结果放回缓存。它不取消锁外的读取或摘要计算；尚未登记的并发调用可在清空后登记并重新驻留，后续新调用也可重新填充。`close()` 另外拒绝新加载；已开始的解析可完成并向原调用方返回，但不能再驻留。清空和关闭都不会使已返回字体失效。

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

`withEngine` 观察调用方已打开的同步 `ExecutionContext`，不自行创建超时或后台任务。默认栈及具名角色栈都传递字体加载检查点，业务回调开始前再检查一次；等待中取消／超时不会启动该回调，也不取消同字体的其他解析者。没有外层执行范围时，不额外引入取消或截止时间。

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

统一入口也可通过 `Export4j.builder(family).pdfBackend(backend)` 借用该后端及解析池；关闭 exporter 不关闭调用方的池。配置示例见[统一入口](easy-export-api.md#生命周期、预算与输出)。字体快照、操作期绑定和 PDF 解析租约是不同层级，不把 PDF 文档对象放入共享字体缓存。

`clear()`／`close()` 立即移除空闲项，在用项等最后一次借用归还后销毁；不会在输出中途关闭字体。实例关闭后不再接受新借用。失败或取消的导出不会把未确认可复用的解析对象交给后续导出。异步清理失败可通过统计观察，并在后续操作中显式报告。

## 释放与边界

空闲维护任务使用守护线程，仅做缓存清理，不执行文档解析、布局或输出；不继承请求线程的可继承线程本地变量、上下文类加载器或 `ExecutionContext`。空缓存及关闭时停止维护任务。到期清理受 JVM 调度影响，不是实时期限承诺。

移出缓存表示释放该缓存的引用。应用仍持有的 `FontFace`、`FontGroup`、`DocumentEngine`、导出会话及第三方库内部引用依然存活；堆数组何时回收由 JVM 决定。不调用 `System.gc()`，不承诺 TTL 到点时 RSS 立即下降。可选公式模块的固定五款数学字体及上游内部缓存具有独立生命周期，不进入本页的 PDF 解析池。

本次不添加系统字体发现、字体替代、持久化解析对象、字体文件变更监听或全格式字体解析池。静态 CFF、TTC、可变／彩色字体继续遵循各自既有入口和预算，不能从水平 TrueType 复用外推收益。

验证命令与实际结果见[字体缓存验收](verification/font-cache.md)，资源所有权决定见 [ADR-0114](adr/0114-idle-expiring-font-caches.md)。

不可变 bytes 来源标识复用与锁外冷解析的接入证据见[架构优化验收](verification/architecture-convergence.md)。
