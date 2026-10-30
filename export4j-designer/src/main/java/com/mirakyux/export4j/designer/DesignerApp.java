// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.designer;

import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.processing.DocumentConversion;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional loopback-only HTML designer. The Java engine performs all document work. */
public final class DesignerApp {
    private DesignerApp() { }
    /** Starts a local service; optional --port=0 chooses an available port. Nothing is uploaded. */
    public static void main(String[] arguments) throws Exception {
        int port = 8784;
        for (String argument : arguments) {
            if (!argument.startsWith("--port=")) { throw new IllegalArgumentException("Usage: DesignerApp [--port=8784]"); }
            port = Integer.parseInt(argument.substring(7));
        }
        var server = new Server(port); server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "designer-shutdown"));
        System.out.println("Export4j Designer: " + server.origin());
    }
    /** One workspace per process. Revision checks prevent silent concurrent-tab overwrites. */
    static final class Server implements AutoCloseable {
        private final HttpServer http;
        private final ThreadPoolExecutor workers;
        private final String token = UUID.randomUUID().toString();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private DesignerProject.History history = new DesignerProject.History(DesignerSamples.create(0));
        private long revision = 1;
        private DesignerService.Preview preview;
        private String previewId = "";
        private DocumentConversion.Prepared prepared;
        private String preparedId = "";
        private DesignerService.Format preparedFormat;
        Server(int port) throws IOException {
            http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
            workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16));
            http.setExecutor(workers); http.createContext("/", this::handle);
        }
        void start() { http.start(); }
        String origin() { return "http://127.0.0.1:" + http.getAddress().getPort(); }
        @Override public void close() { cancelled.set(true); http.stop(0); workers.shutdownNow(); }
        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
                exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
                try {
                    if (!("127.0.0.1:" + http.getAddress().getPort()).equals(exchange.getRequestHeaders().getFirst("Host"))) { throw new Rejected(403, "loopback Host required"); }
                    String path = exchange.getRequestURI().getPath();
                    if (!path.startsWith("/api/")) { asset(exchange, path); return; }
                    String supplied = exchange.getRequestHeaders().getFirst("X-Designer-Token");
                    if (supplied == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) { throw new Rejected(403, "designer session token required"); }
                    String origin = exchange.getRequestHeaders().getFirst("Origin");
                    if (origin != null && !origin().equals(origin)) { throw new Rejected(403, "cross-origin request rejected"); }
                    if (path.equals("/api/cancel")) { method(exchange, "POST"); cancelled.set(true); json(exchange, Map.of("cancelled", true)); return; }
                    synchronized (this) { api(exchange, path); }
                } catch (Rejected ex) { error(exchange, ex.status, ex.getMessage()); }
                catch (ProcessingException ex) {
                    error(exchange, 422, BindingJson.object("error", "当前策略拒绝不兼容内容，未生成下载文件。",
                            "report", report(new ProcessingReport(ProcessingReport.Phase.PREVIEW, List.of(ex.issue())))));
                }
                catch (Exception ex) { error(exchange, 422, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()); }
            }
        }
        private void asset(HttpExchange exchange, String path) throws IOException {
            method(exchange, "GET");
            String name = switch (path) { case "/" -> "index.html"; case "/app.js" -> "app.js"; case "/editing.js" -> "editing.js"; case "/tables.js" -> "tables.js"; case "/canvas.js" -> "canvas.js"; case "/interactions.js" -> "interactions.js"; case "/workspace.js" -> "workspace.js"; case "/style.css" -> "style.css"; default -> throw new Rejected(404, "not found"); };
            try (var input = DesignerApp.class.getResourceAsStream("/designer/web/" + name)) {
                if (input == null) { throw new IOException("designer web resource missing"); }
                byte[] bytes = input.readAllBytes();
                if (name.equals("index.html")) { bytes = new String(bytes, StandardCharsets.UTF_8).replace("__DESIGNER_TOKEN__", token).getBytes(StandardCharsets.UTF_8); }
                send(exchange, name.endsWith(".js") ? "text/javascript; charset=utf-8" : name.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8", bytes);
            }
        }
        private void api(HttpExchange exchange, String path) throws Exception {
            if (path.equals("/api/state")) { method(exchange, "GET"); json(exchange, state()); return; }
            if (path.equals("/api/image")) {
                method(exchange, "GET");
                String query = exchange.getRequestURI().getRawQuery();
                if (query == null || !query.matches("name=[A-Za-z_][A-Za-z_0-9-]*&revision=[0-9]+")) { throw new Rejected(400, "image name and revision required"); }
                int separator = query.indexOf('&');
                if (Long.parseLong(query.substring(separator + 10)) != revision) { throw new Rejected(409, "image preview expired"); }
                var image = history.current().bundle().images().get(query.substring(5, separator));
                if (image == null) { throw new Rejected(404, "image resource not found"); }
                send(exchange, image.mediaType(), image.bytes()); return;
            }
            if (path.equals("/api/page")) {
                method(exchange, "GET");
                if (preview == null) { throw new Rejected(409, "preview needs refresh"); }
                String query = exchange.getRequestURI().getRawQuery();
                if (query == null || !query.matches("index=[0-9]{1,3}(&revision=[0-9]+)?(&preview=[a-fA-F0-9-]{36})?")) { throw new Rejected(400, "page index required"); }
                int separator = query.indexOf('&');
                if (separator >= 0) {
                    String[] parts = query.substring(separator + 1).split("&");
                    for (String part : parts) {
                        if (part.startsWith("revision=") && Long.parseLong(part.substring(9)) != revision
                                || part.startsWith("preview=") && !part.substring(8).equals(previewId)) {
                            throw new Rejected(409, "page preview expired");
                        }
                    }
                }
                int index = Integer.parseInt(separator < 0 ? query.substring(6) : query.substring(6, separator));
                if (index >= preview.layout().display().pages().size()) { throw new Rejected(400, "page index out of range"); }
                byte[] encoded = com.mirakyux.export4j.model.ExecutionContext.execute(DesignerService.execution(cancelled::get), () -> {
                    var image = preview.image(index); var bytes = new java.io.ByteArrayOutputStream();
                    try { javax.imageio.ImageIO.write(image, "png", bytes); } finally { image.flush(); }
                    return bytes.toByteArray();
                });
                send(exchange, "image/png", encoded); return;
            }
            method(exchange, "POST");
            var request = BindingJson.parse(utf8(read(exchange, 24 * 1024 * 1024)), 24 * 1024 * 1024);
            if (integer(request, "revision") != revision) { throw new Rejected(409, "workspace changed in another tab; reload before editing"); }
            cancelled.set(false);
            switch (path) {
                case "/api/format" -> {
                    String formatted = switch (text(request, "kind")) {
                        case "xml" -> DesignerFormatting.xml(text(request, "source"));
                        case "json" -> DesignerFormatting.json(text(request, "source"));
                        default -> throw new IllegalArgumentException("format accepts XML or JSON");
                    };
                    json(exchange, Map.of("formatted", formatted)); return;
                }
                case "/api/apply" -> change(history.current().source(text(request, "source"), text(request, "data")));
                case "/api/new" -> {
                    var project = switch (request.containsKey("kind") ? text(request, "kind") : "sample") {
                        case "blank" -> DesignerSamples.blank();
                        case "sample" -> DesignerSamples.create(integer(request, "sample"));
                        default -> throw new IllegalArgumentException("new accepts blank or sample");
                    };
                    history = new DesignerProject.History(project); changed();
                }
                case "/api/undo" -> { history.undo(); changed(); }
                case "/api/redo" -> { history.redo(); changed(); }
                case "/api/edit" -> edit(request);
                case "/api/open" -> {
                    byte[] bytes = decode(request); XmlTemplateBundle bundle;
                    if (text(request, "type").equals("xml")) { bundle = DesignerService.defaults(utf8(bytes)).build(); }
                    else if (text(request, "type").equals("zip")) {
                        bundle = XmlTemplateBundle.read(new ByteArrayInputStream(bytes), new XmlTemplateBundle.Limits(16 * 1024 * 1024, 16 * 1024 * 1024, 256, 1_000_000, 8_000_000), DesignerService.execution(cancelled::get));
                    } else { throw new IllegalArgumentException("open accepts XML or ZIP"); }
                    var candidate = new DesignerProject(bundle, "{}").source(bundle.source(), "{}");
                    history = new DesignerProject.History(candidate); changed();
                }
                case "/api/resource" -> resource(request);
                case "/api/save" -> { download(exchange, "template.zip", "application/zip", history.current().bundle().encode()); return; }
                case "/api/preview" -> {
                    long start = System.nanoTime();
                    var project = history.current();
                    if (request.containsKey("source") || request.containsKey("data")) {
                        project = project.source(request.containsKey("source") ? text(request, "source") : project.bundle().source(),
                                request.containsKey("data") ? text(request, "data") : project.data());
                    }
                    var candidate = DesignerService.preview(project, DesignerService.execution(cancelled::get));
                    preview = candidate; previewId = UUID.randomUUID().toString(); var pages = new ArrayList<Object>();
                    for (int index = 0; index < candidate.layout().display().pages().size(); index++) {
                        var page = candidate.layout().display().pages().get(index); int number = index;
                        pages.add(BindingJson.object("width", page.size().width(), "height", page.size().height(), "hits", candidate.hits().stream().filter(hit -> hit.page() == number)
                                .map(hit -> BindingJson.object("id", hit.id(), "path", hit.path(), "x", hit.x(), "y", hit.y(), "width", hit.width(), "height", hit.height(),
                                        "images", hit.images().stream().map(image -> BindingJson.object("x", image.x(), "y", image.y(), "width", image.width(), "height", image.height())).toList())).toList(),
                                "breaks", candidate.breaks().stream().filter(item -> item.page() == number).map(item -> BindingJson.object("id", item.id(), "path", item.path(), "y", item.y())).toList()));
                    }
                    json(exchange, BindingJson.object("revision", revision, "previewId", previewId, "pages", pages, "milliseconds", (System.nanoTime() - start) / 1_000_000,
                            "diagnostics", candidate.layout().contextualDiagnostics().stream().map(Object::toString).toList())); return;
                }
                case "/api/export/prepare" -> {
                    prepared = null; preparedId = "";
                    var format = DesignerService.Format.valueOf(text(request, "format"));
                    var policy = DesignerService.policy(format, ProcessingPolicy.Action.valueOf(text(request, "action")), text(request, "treatment"));
                    var candidate = DesignerService.prepare(history.current(), format, policy, DesignerService.execution(cancelled::get));
                    prepared = candidate; preparedId = UUID.randomUUID().toString(); preparedFormat = format;
                    json(exchange, BindingJson.object("id", preparedId, "report", report(candidate.preview()))); return;
                }
                case "/api/export/download" -> {
                    if (prepared == null || !preparedId.equals(text(request, "id"))) { throw new Rejected(409, "export preflight expired"); }
                    var bytes = new java.io.ByteArrayOutputStream(); prepared.to(bytes);
                    download(exchange, "document." + preparedFormat.extension, "application/octet-stream", bytes.toByteArray()); return;
                }
                default -> throw new Rejected(404, "unknown operation");
            }
            json(exchange, state());
        }
        private void edit(Map<String, Object> request) throws Exception {
            String source = history.current().bundle().source(); String path = text(request, "path");
            source = switch (text(request, "operation")) {
                case "merge-cells" -> DesignerTableEdits.merge(source, path, paths(request));
                case "split-cell" -> DesignerTableEdits.split(source, path, text(request, "cell"));
                case "insert" -> DesignerXml.insert(source, path, text(request, "xml"));
                case "insert-beside" -> DesignerXml.insertBeside(source, path, text(request, "xml"), Boolean.TRUE.equals(request.get("after")));
                case "start-section" -> DesignerXml.startSection(source, path, text(request, "id"), text(request, "initialId"));
                case "replace" -> DesignerXml.replace(source, path, text(request, "xml"));
                case "move" -> {
                    int direction = integer(request, "direction"); if (direction != -1 && direction != 1) { throw new IllegalArgumentException("move direction must be -1 or 1"); }
                    yield DesignerXml.move(source, path, direction);
                }
                case "relocate" -> DesignerXml.relocate(source, path, text(request, "target"), Boolean.TRUE.equals(request.get("after")));
                case "remove" -> DesignerXml.remove(source, path);
                case "batch-remove" -> DesignerXml.removeMany(source, paths(request));
                case "batch-relocate" -> DesignerXml.relocateMany(source, paths(request), text(request, "target"), Boolean.TRUE.equals(request.get("after")));
                case "batch-properties" -> {
                    var paths = paths(request);
                    var changes = stringAttributes(request);
                    if (paths.size() > 1 && changes.containsKey("id")) { throw new IllegalArgumentException("ID must be unique; edit it on one element at a time"); }
                    yield DesignerXml.patchProperties(source, paths, changes);
                }
                case "properties" -> {
                    if (!(request.get("attributes") instanceof Map<?, ?> attributes)) { throw new IllegalArgumentException("attributes object required"); }
                    var values = new LinkedHashMap<String, String>();
                    for (var item : attributes.entrySet()) {
                        if (!(item.getKey() instanceof String key) || !(item.getValue() instanceof String value)) { throw new IllegalArgumentException("attribute names and values must be strings"); }
                        values.put(key, value);
                    }
                    yield DesignerXml.properties(source, path, values, Boolean.TRUE.equals(request.get("plain")) ? text(request, "text") : null);
                }
                default -> throw new IllegalArgumentException("unknown edit operation");
            };
            change(history.current().source(source, request.containsKey("data") ? text(request, "data") : history.current().data()));
        }
        private static List<String> paths(Map<String, Object> request) {
            if (!(request.get("paths") instanceof List<?> values) || values.stream().anyMatch(value -> !(value instanceof String))) {
                throw new IllegalArgumentException("paths array required");
            }
            return values.stream().map(String.class::cast).toList();
        }
        private static Map<String, String> stringAttributes(Map<String, Object> request) {
            if (!(request.get("attributes") instanceof Map<?, ?> values)) { throw new IllegalArgumentException("attributes object required"); }
            var changes = new LinkedHashMap<String, String>();
            for (var entry : values.entrySet()) {
                if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String value)) {
                    throw new IllegalArgumentException("attribute names and values must be strings");
                }
                changes.put(key, value);
            }
            return changes;
        }
        private void resource(Map<String, Object> request) throws Exception {
            var builder = history.current().bundle().toBuilder(); String name = text(request, "name"); byte[] bytes = decode(request);
            switch (text(request, "kind")) {
                case "image" -> builder.image(name, ImageResource.load(bytes));
                case "fragment" -> builder.fragment(name, utf8(bytes));
                case "font" -> {
                    if (!List.of("Regular.ttf", "Bold.ttf", "Chinese.ttf").contains(name)) { throw new IllegalArgumentException("choose an explicit Regular/Bold/Chinese font role"); }
                    String license = text(request, "license"); if (license.isBlank() || license.length() > 100_000) { throw new IllegalArgumentException("font license text required, at most 100,000 UTF-16 units"); }
                    com.mirakyux.export4j.font.TrueTypeFontLoader.load(bytes);
                    builder.font(name, bytes).license(name.replace(".ttf", "-license.txt"), license);
                }
                default -> throw new IllegalArgumentException("unknown resource kind");
            }
            var candidate = new DesignerProject(builder.build(), history.current().data()); candidate.bundle().compile(); change(candidate);
        }
        private void change(DesignerProject candidate) { history.change(candidate); changed(); }
        private void changed() { revision++; preview = null; previewId = ""; prepared = null; preparedId = ""; }
        private Map<String, Object> state() throws Exception {
            var project = history.current(); var fonts = new ArrayList<Object>();
            project.bundle().fonts().forEach((name, bytes) -> fonts.add(BindingJson.object("name", name, "bytes", bytes.length)));
            var images = new ArrayList<Object>(); project.bundle().images().forEach((name, image) -> images.add(BindingJson.object("name", name, "width", image.pixelWidth(), "height", image.pixelHeight())));
            return BindingJson.object("revision", revision, "source", project.bundle().source(), "data", project.data(), "outline", entry(DesignerXml.outline(DesignerXml.parse(project.bundle().source()).getDocumentElement(), "")),
                    "fonts", fonts, "images", images, "fragments", project.bundle().fragments(), "licenses", project.bundle().licenses().keySet().stream().sorted().toList(),
                    "canUndo", history.canUndo(), "canRedo", history.canRedo(), "samples", DesignerSamples.catalog());
        }
        private static Map<String, Object> entry(DesignerXml.Entry entry) { return BindingJson.object("path", entry.path(), "tag", entry.tag(), "id", entry.id(), "label", entry.label(), "children", entry.children().stream().map(Server::entry).toList()); }
        private static Object report(ProcessingReport report) {
            return BindingJson.object("phase", report.phase().name(), "losses", report.losses().size(), "warnings", report.warnings().size(), "issues", report.issues().stream()
                    .map(issue -> BindingJson.object("feature", issue.feature(), "reason", issue.reason(), "action", issue.action().name(), "treatment", issue.treatment().name(),
                            "block", issue.location().blockId(), "section", issue.location().sectionId(), "region", issue.location().region(),
                            "startUtf16", issue.location().startUtf16(), "endUtf16", issue.location().endUtf16(),
                            "cells", issue.location().cellPath().stream().map(cell -> BindingJson.object("table", cell.tableId(), "row", cell.rowIndex(), "column", cell.columnIndex())).toList(),
                            "effects", issue.effects())).toList());
        }
        private static String text(Map<String, Object> request, String key) { if (!(request.get(key) instanceof String value)) { throw new IllegalArgumentException("string required: " + key); } return value; }
        private static int integer(Map<String, Object> request, String key) { if (!(request.get(key) instanceof java.math.BigDecimal value)) { throw new IllegalArgumentException("integer required: " + key); } return value.intValueExact(); }
        private static byte[] decode(Map<String, Object> request) { byte[] bytes = Base64.getDecoder().decode(text(request, "bytes")); if (bytes.length > 16 * 1024 * 1024) { throw new IllegalArgumentException("resource exceeds 16 MiB"); } return bytes; }
        private static String utf8(byte[] bytes) throws IOException { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        private static byte[] read(HttpExchange exchange, int limit) throws IOException { byte[] bytes = exchange.getRequestBody().readNBytes(limit + 1); if (bytes.length > limit) { throw new Rejected(413, "request byte budget exceeded"); } return bytes; }
        private static void method(HttpExchange exchange, String method) { if (!exchange.getRequestMethod().equals(method)) { throw new Rejected(405, "method not allowed"); } }
        private static void json(HttpExchange exchange, Object value) throws IOException { send(exchange, "application/json; charset=utf-8", BindingJson.write(value).getBytes(StandardCharsets.UTF_8)); }
        private static void download(HttpExchange exchange, String name, String type, byte[] bytes) throws IOException { exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name + "\""); send(exchange, type, bytes); }
        private static void send(HttpExchange exchange, String type, byte[] bytes) throws IOException { exchange.getResponseHeaders().set("Content-Type", type); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); }
        private static void error(HttpExchange exchange, int status, String reason) throws IOException {
            error(exchange, status, Map.of("error", reason));
        }
        private static void error(HttpExchange exchange, int status, Map<String, Object> failure) throws IOException {
            byte[] bytes = BindingJson.write(failure).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
        }
        private static final class Rejected extends RuntimeException {
            private static final long serialVersionUID = 1L;
            private final int status;
            Rejected(int status, String message) { super(message); this.status = status; }
        }
    }
}
