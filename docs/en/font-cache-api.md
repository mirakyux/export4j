# Font caching and idle release

The development version reuses identical font content with entry, encoded-source-byte and idle-time limits. Cleanup also runs without later requests. This feature is absent from published `0.1.0-rc.3`. No system-font scan, user-home font index or FontBox source patch is added.

| Resource | Default retention | Idle lifetime |
| --- | --- | --- |
| Immutable faces loaded by `TrueTypeFontLoader` | 16 entries / 64 MiB source bytes | Five minutes since the last successful load or hit |
| Parsed horizontal TrueType PDF programs | 8 entries / 32 MiB source bytes | Five minutes after an export returns its exclusive lease |
| Base physical fonts used by JDK text layout | 32 entries / 64 MiB source bytes | Five minutes since the last use |

These limits bound retained source weight, not total heap, native storage or RSS. Oversized inputs use the original per-operation budgets without becoming resident.

## Immutable faces

Existing `TrueTypeFontLoader.load` overloads use the shared cache by default. Keys are content SHA-256, with private input snapshots. Paths are reread; a matching path or timestamp cannot hide changed bytes. Every call checks its byte limit, including hits. Borrowed streams stay caller-owned. Failed parses and permission failures are not cached.

`FontSource.bytes(...)` creates an internal `SnapshotFontSource` holding a private encoded snapshot and its SHA-256 identity. Later loads reuse that immutable identity; callers cannot independently supply bytes and an asserted digest. Public `FontFaceCache.load(byte[])` still protects external arrays on every call. File and class-resource sources continue reading on each load, so the snapshot path cannot hide file changes.

Input reads, snapshot hashing, and cold parsing execute outside the cache lock. The lock coordinates hits, capacity, retirement, and result registration. Bounded same-key pending entries share an in-progress parse result. Coordination entries and source bytes have their own bounds using the cache configuration; calls exceeding those bounds parse independently under existing budgets. This does not cap the total memory of all concurrent parsing in an application.

Use `FontFaceCache.load(source, checkpoint)` or `load(source, maxBytes, checkpoint)` to observe cancellation or deadlines while waiting for the same content; source can be bytes, a borrowed stream or an explicit Path. Checkpoints run synchronously on the caller thread outside the cache lock, with a 25 ms recheck interval during waiting, subject to JVM scheduling. A callback failure stops only that caller, leaving the shared parser and other callers intact. Synchronous I/O and font parsing remain non-preemptible phases.

The default `FontSource.load(cache, checkpoint)` preserves the original functional interface and lambdas. Built-in byte/file/resource sources propagate the checkpoint into cache waiting. Custom sources are checked before and after their original method; they must override the new overload and forward the checkpoint to cooperate inside internal waits. The starter supplies `ExecutionContext::checkpoint`, without adding a model dependency or global execution context to the font module.

```java
try (var fonts = FontFaceCache.builder()
        .maxEntries(8)
        .maxSourceBytes(32L * 1024 * 1024)
        .idleTimeout(Duration.ofMinutes(2))
        .build()) {
    var face = fonts.load(Path.of("fonts/body.ttf"));
    var stats = fonts.stats();
    fonts.clear();
    // face remains usable; cache eviction never invalidates caller-owned references.
}
```

Use `com.mirakyux.export4j.font.FontFaceCache`, `java.time.Duration` and `java.nio.file.Path`. Instance `stats()` is passive, `clear()` drops entries and `close()` rejects future loads. `TrueTypeFontLoader.cacheStats()` / `clearCache()` observe or clear the default cache. An instance built with `enabled(false)` parses without retention.

`clear()` removes retained faces and retires parses already registered at that moment. Those parses may still return a face to their callers, but cannot retain that result. Clear does not cancel reads or hashing outside the lock: a call not yet registered may register after clear and become resident, as may future calls. `close()` also rejects new loads. Parsing already underway may return to its original caller without retaining its result. Neither operation invalidates faces already returned.

## Long-lived services

Cache expiry cannot release an engine or font group retained by an application singleton. Store explicit reopenable sources and perform exports inside a short-lived engine operation:

```java
try (var provider = DocumentEngineProvider.builder(List.of(
        FontSource.file(Path.of("fonts/body.ttf")))).build()) {
    provider.withEngine(engine -> {
        engine.write(document, new PdfBackend(), outputPath);
        return null;
    });
}
```

Use `components.DocumentEngineProvider` and `font.FontSource` under the project root package. Sources accept explicit files, class resources or private encoded-byte snapshots. They retain no parsed face and scan no directories; byte sources retain their encoded snapshot while configured. A provider owns its default bounded cache or borrows one supplied with `fontCache`. Declare separate role stacks with `fontRole(name, sources)`.

Clearing or closing does not invalidate active fonts. Close rejects new operations. Returned layouts or engines captured by the caller may still retain fonts until released; no document cache, forced collection or total-heap guarantee is introduced.

## PDF programs

`PdfBackend.write`, page sessions and `PdfSpoolBackend` use the bounded default pool. A parsed font belongs to only one export lease at a time. Each document retains its own `PDFont`, COS objects, mappings and subsets. Ordinary writes do not enable the spool-only subset snapshot optimization.

```java
try (var programs = PdfFontCache.builder()
        .maxEntries(4)
        .maxSourceBytes(32L * 1024 * 1024)
        .idleTimeout(Duration.ofMinutes(2))
        .build()) {
    var backend = PdfBackend.builder().fontCache(programs).build();
    // Reuse backend for independent exports; each export borrows independently.
    var stats = programs.stats();
    programs.clear();
}
```

Use `com.mirakyux.export4j.backend.pdf.PdfFontCache` / `PdfBackend`. Backends borrow custom pools; callers close them. Spooling accepts `new PdfSpoolBackend(directory, pageLimits, spoolLimits, programs)`. `PdfFontCache.defaultStats()` / `clearDefault()` observe or clear the default pool. `enabled(false)` retains the export algorithm while disabling retention.

The unified entry can borrow the same backend and pool with `Export4j.builder(family).pdfBackend(backend)`. Closing the exporter does not close a caller-owned pool. See the [unified entry configuration](easy-export-api.md#lifetime-budgets-and-output). Encoded snapshots, operation-local font bindings, and PDF program leases are separate layers; shared font caches do not retain PDF documents.

Clear/close remove idle entries; active entries are retired only after their borrowers finish. Closed pools reject new borrows. Failed or cancelled exports do not return unverified programs for later reuse. Asynchronous cleanup failures are observable through statistics and subsequent operations.

## Lifetime boundaries

Maintenance uses lazy daemon threads which stop when empty or closed. They perform only idle cleanup, inherit no request thread-local state or context class loader, and never run document parsing, layout or output. Idle deadlines remain subject to JVM scheduling.

Expiration releases cache references. Faces, font groups, engines and active exports still held by the application remain usable. JVM collection determines when heap is reclaimed; no `System.gc()` or immediate RSS reduction is promised. The optional math module's five fixed fonts and upstream internal caches have separate lifetimes.

The pool covers horizontal static TrueType programs; CFF, TTC, variable/color fonts and other paths retain their existing contracts. See [verification (Chinese)](../verification/font-cache.md) and [ADR-0114 (Chinese)](../adr/0114-idle-expiring-font-caches.md).
