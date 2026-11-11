// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.designer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class DesignerServerTest {
    @Test void exportsReturnStructuredRejectionsAndKeepWarningAndIgnoreLosses() throws Exception {
        try (var server = new DesignerApp.Server(0); var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            server.start();
            var home = client.send(HttpRequest.newBuilder(URI.create(server.origin() + "/")).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
            var match = Pattern.compile("name=\"designer-token\" content=\"([^\"]+)\"").matcher(home.body());
            assertTrue(match.find()); String token = match.group(1);
            var initial = BindingJson.parse(send(client, server.origin(), "state", token, null, null).body());
            String source = "<document version='1'><p id='turned' rotation='30'>ROTATION-EXPORT</p></document>";
            var applied = send(client, server.origin(), "apply", token,
                    BindingJson.write(BindingJson.object("revision", initial.get("revision"), "source", source, "data", "{}")), null);
            assertEquals(200, applied.statusCode());
            var state = BindingJson.parse(applied.body()); Object revision = state.get("revision");
            String lastId = "";
            for (String action : List.of("WARN", "IGNORE")) {
                var response = send(client, server.origin(), "export/prepare", token,
                        BindingJson.write(BindingJson.object("revision", revision, "format", "DOCX", "action", action, "treatment", "RECOMMENDED")), null);
                assertEquals(200, response.statusCode());
                var prepared = BindingJson.parse(response.body());
                var report = (Map<?, ?>) prepared.get("report");
                assertEquals("PREVIEW", report.get("phase"));
                assertEquals("1", report.get("losses").toString());
                assertEquals(action.equals("WARN") ? "1" : "0", report.get("warnings").toString());
                var issue = (Map<?, ?>) ((List<?>) report.get("issues")).getFirst();
                assertEquals("ELEMENT_ROTATION", issue.get("feature"));
                assertEquals("turned", issue.get("block"));
                assertEquals("BODY", issue.get("region"));
                assertEquals(action, issue.get("action"));
                assertEquals(List.of(), issue.get("cells"));
                lastId = (String) prepared.get("id");
                var download = send(client, server.origin(), "export/download", token,
                        BindingJson.write(BindingJson.object("revision", revision, "id", lastId)), null);
                assertEquals(200, download.statusCode()); assertTrue(download.body().startsWith("PK"));
                assertTrue(download.headers().firstValue("Content-Disposition").orElseThrow().contains("document.docx"));
            }
            var rejected = send(client, server.origin(), "export/prepare", token,
                    BindingJson.write(BindingJson.object("revision", revision, "format", "DOCX", "action", "ERROR", "treatment", "RECOMMENDED")), null);
            assertEquals(422, rejected.statusCode());
            var failure = BindingJson.parse(rejected.body());
            var rejectedReport = (Map<?, ?>) failure.get("report");
            var rejectedIssue = (Map<?, ?>) ((List<?>) rejectedReport.get("issues")).getFirst();
            assertEquals("ELEMENT_ROTATION", rejectedIssue.get("feature"));
            assertEquals("turned", rejectedIssue.get("block"));
            assertEquals("ERROR", rejectedIssue.get("action"));
            assertEquals(409, send(client, server.origin(), "export/download", token,
                    BindingJson.write(BindingJson.object("revision", revision, "id", lastId)), null).statusCode());
            var lossless = send(client, server.origin(), "export/prepare", token,
                    BindingJson.write(BindingJson.object("revision", revision, "format", "PDF", "action", "ERROR", "treatment", "RECOMMENDED")), null);
            assertEquals(200, lossless.statusCode());
            var pdf = BindingJson.parse(lossless.body());
            assertEquals("0", ((Map<?, ?>) pdf.get("report")).get("losses").toString());
            var downloaded = send(client, server.origin(), "export/download", token,
                    BindingJson.write(BindingJson.object("revision", revision, "id", pdf.get("id"))), null);
            assertEquals(200, downloaded.statusCode()); assertTrue(downloaded.body().startsWith("%PDF-"));
            assertEquals(422, send(client, server.origin(), "export/prepare", token,
                    BindingJson.write(BindingJson.object("revision", revision, "format", "PDF", "action", "ERROR", "treatment", "INVALID")), null).statusCode());
            assertEquals(409, send(client, server.origin(), "export/download", token,
                    BindingJson.write(BindingJson.object("revision", revision, "id", pdf.get("id"))), null).statusCode());
            assertEquals(state, BindingJson.parse(send(client, server.origin(), "state", token, null, null).body()));
        }
    }
    @Test void newDocumentDistinguishesBlankFromCatalogSamplesAndRejectsInvalidChoicesAtomically() throws Exception {
        try (var server = new DesignerApp.Server(0); var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            server.start();
            var home = client.send(HttpRequest.newBuilder(URI.create(server.origin() + "/")).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
            var match = Pattern.compile("name=\"designer-token\" content=\"([^\"]+)\"").matcher(home.body());
            assertTrue(match.find()); String token = match.group(1);
            var state = BindingJson.parse(send(client, server.origin(), "state", token, null, null).body());
            var samples = (java.util.List<?>) state.get("samples");
            assertEquals(14, samples.size());
            assertEquals("药品不良反应报告表", ((Map<?, ?>) samples.get(4)).get("name"));
            assertEquals("项目周报", ((Map<?, ?>) samples.get(10)).get("name"));
            var blank = send(client, server.origin(), "new", token,
                    BindingJson.write(BindingJson.object("revision", state.get("revision"), "kind", "blank")), null);
            assertEquals(200, blank.statusCode());
            state = BindingJson.parse(blank.body());
            assertEquals("{}", state.get("data"));
            assertEquals(DesignerSamples.blank().bundle().source(), state.get("source"));
            for (var choice : java.util.List.of(BindingJson.object("kind", "unknown"), BindingJson.object("kind", "sample", "sample", 99))) {
                var payload = new java.util.LinkedHashMap<String, Object>();
                payload.put("revision", state.get("revision"));
                payload.putAll(choice);
                assertEquals(422, send(client, server.origin(), "new", token, BindingJson.write(payload), null).statusCode());
                assertEquals(state, BindingJson.parse(send(client, server.origin(), "state", token, null, null).body()));
            }
            var sample = send(client, server.origin(), "new", token,
                    BindingJson.write(BindingJson.object("revision", state.get("revision"), "kind", "sample", "sample", 4)), null);
            assertEquals(200, sample.statusCode());
            var created = BindingJson.parse(sample.body());
            assertEquals(DesignerSamples.create(4).data(), created.get("data"));
            assertEquals(Boolean.FALSE, created.get("canUndo"));
        }
    }
    @Test void localServiceRejectsForeignOriginsStaleEditsAndUnsafeSources() throws Exception {
        try (var server = new DesignerApp.Server(0); var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            server.start(); assertTrue(server.origin().startsWith("http://127.0.0.1:"));
            var index = client.send(HttpRequest.newBuilder(URI.create(server.origin() + "/")).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, index.statusCode()); assertTrue(index.headers().firstValue("Content-Security-Policy").orElseThrow().contains("frame-ancestors 'none'"));
            var tokenMatch = Pattern.compile("name=\"designer-token\" content=\"([^\"]+)\"").matcher(index.body()); assertTrue(tokenMatch.find()); String token = tokenMatch.group(1);
            assertEquals(403, send(client, server.origin(), "state", null, null, null).statusCode());
            assertEquals(403, send(client, server.origin(), "state", token, null, "https://foreign.invalid").statusCode());
            var state = BindingJson.parse(send(client, server.origin(), "state", token, null, null).body());
            String revision = state.get("revision").toString(); String source = (String) state.get("source");
            assertEquals(403, send(client, server.origin(), "image?name=mark&revision=" + revision, null, null, null).statusCode());
            var image = send(client, server.origin(), "image?name=mark&revision=" + revision, token, null, null);
            assertEquals(200, image.statusCode()); assertEquals("image/png", image.headers().firstValue("Content-Type").orElseThrow());
            assertEquals(409, send(client, server.origin(), "image?name=mark&revision=0", token, null, null).statusCode());
            assertEquals(400, send(client, server.origin(), "image?name=../Regular.ttf&revision=" + revision, token, null, null).statusCode());
            assertEquals(404, send(client, server.origin(), "image?name=missing&revision=" + revision, token, null, null).statusCode());
            var formatRequest = BindingJson.object("revision", state.get("revision"), "kind", "json", "source", "{\"n\":12345678901234567890}");
            var formatted = send(client, server.origin(), "format", token, BindingJson.write(formatRequest), null);
            assertEquals(200, formatted.statusCode()); assertTrue(formatted.body().contains("12345678901234567890"));
            assertEquals(revision, BindingJson.parse(send(client, server.origin(), "state", token, null, null).body()).get("revision").toString());
            assertEquals(409, send(client, server.origin(), "apply", token, "{\"revision\":0,\"source\":\"\",\"data\":\"{}\"}", null).statusCode());
            var bad = BindingJson.object("revision", state.get("revision"), "source", "<!DOCTYPE document><document version='1'/>", "data", "{}");
            assertEquals(422, send(client, server.origin(), "apply", token, BindingJson.write(bad), null).statusCode());
            var unchanged = BindingJson.parse(send(client, server.origin(), "state", token, null, null).body());
            assertEquals(source, unchanged.get("source")); assertEquals(revision, unchanged.get("revision").toString());
            assertEquals(404, send(client, server.origin(), "unavailable", token, "{\"revision\":" + revision + "}", null).statusCode());
            var preview = send(client, server.origin(), "preview", token, BindingJson.write(Map.of("revision", state.get("revision"))), null); assertEquals(200, preview.statusCode());
            assertTrue(preview.body().contains("report-title"));
            assertTrue(preview.body().contains("\"breaks\""));
            var firstPreviewId=BindingJson.parse(preview.body()).get("previewId");
            var draftRequest=BindingJson.object("revision",state.get("revision"),"source",source.replace("REPORT-END","DRAFT-ONLY"),"data",state.get("data"));
            var draft=send(client,server.origin(),"preview",token,BindingJson.write(draftRequest),null);
            assertEquals(200,draft.statusCode());
            var draftPreviewId=BindingJson.parse(draft.body()).get("previewId");
            assertEquals(409,send(client,server.origin(),"page?index=0&revision="+revision+"&preview="+firstPreviewId,token,null,null).statusCode());
            assertEquals(200,send(client,server.origin(),"page?index=0&revision="+revision+"&preview="+draftPreviewId,token,null,null).statusCode());
            assertEquals(source,BindingJson.parse(send(client,server.origin(),"state",token,null,null).body()).get("source"));
            assertEquals(revision,BindingJson.parse(send(client,server.origin(),"state",token,null,null).body()).get("revision").toString());
            var invalidDraft=BindingJson.object("revision",state.get("revision"),"source","<document>","data",state.get("data"));
            assertEquals(422,send(client,server.origin(),"preview",token,BindingJson.write(invalidDraft),null).statusCode());
            assertEquals(200,send(client,server.origin(),"page?index=0&revision="+revision+"&preview="+draftPreviewId,token,null,null).statusCode());
            assertEquals(source,BindingJson.parse(send(client,server.origin(),"state",token,null,null).body()).get("source"));
            assertEquals(409, send(client, server.origin(), "page?index=0&revision=0", token, null, null).statusCode());
            var update = BindingJson.object("revision", state.get("revision"), "source", source.replace("REPORT-END", "SAVED-CHANGE"), "data", state.get("data"));
            var changed = send(client, server.origin(), "apply", token, BindingJson.write(update), null); assertEquals(200, changed.statusCode()); assertTrue(changed.body().contains("SAVED-CHANGE"));
            assertEquals(409, send(client, server.origin(), "page?index=0", token, null, null).statusCode());
            var sample = BindingJson.parse(send(client, server.origin(), "new", token,
                    BindingJson.write(BindingJson.object("revision", BindingJson.parse(changed.body()).get("revision"), "sample", 1)), null).body());
            String listData = "{\"items\":[{\"name\":\"示例项\"}]}";
            var insert = BindingJson.object("revision", sample.get("revision"), "operation", "insert", "path", "/1",
                    "xml", "<list items='items' as='item'><bullet id='new-item'>${item.name}</bullet></list>", "data", listData);
            var inserted = send(client, server.origin(), "edit", token, BindingJson.write(insert), null);
            assertEquals(200, inserted.statusCode());
            var insertedState = BindingJson.parse(inserted.body());
            assertEquals(listData, insertedState.get("data"));
            assertTrue(insertedState.get("source").toString().contains("id=\"new-item\""));
            assertEquals(200, send(client, server.origin(), "preview", token,
                    BindingJson.write(Map.of("revision", insertedState.get("revision"))), null).statusCode());
            var undone = BindingJson.parse(send(client, server.origin(), "undo", token,
                    BindingJson.write(Map.of("revision", insertedState.get("revision"))), null).body());
            assertEquals("{}", undone.get("data"));
            assertEquals(sample.get("source"), undone.get("source"));
        }
    }
    @Test void appAssetsHaveNoArbitraryFilesystemRoute() throws Exception {
        try (var server = new DesignerApp.Server(0); var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            server.start();
            for (String path : new String[]{"/AGENTS.md", "/../AGENTS.md", "/assets/fonts/Regular.ttf"}) {
                var response = client.send(HttpRequest.newBuilder(URI.create(server.origin() + path)).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(404, response.statusCode());
            }
        }
    }
    @Test void imageAndSourceCommitTogetherAndUndoAsOneChange() throws Exception {
        try (var server = new DesignerApp.Server(0); var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            server.start();
            var home = client.send(HttpRequest.newBuilder(URI.create(server.origin() + "/")).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
            var match = Pattern.compile("name=\"designer-token\" content=\"([^\"]+)\"").matcher(home.body());
            assertTrue(match.find()); String token = match.group(1);
            var initial = BindingJson.parse(send(client, server.origin(), "state", token, null, null).body());
            String original = "<document version='1'><p id='kept'>KEPT ${name}</p></document>";
            String data = "{\"name\":\"Atomic\"}";
            var before = BindingJson.parse(send(client, server.origin(), "apply", token,
                    BindingJson.write(BindingJson.object("revision", initial.get("revision"), "source", original, "data", data)), null).body());
            String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a/QAAAABJRU5ErkJggg==";
            String source = "<document version='1'><p id='kept'>KEPT ${name}</p><p id='pasted'><image ref='atomic_image' width='12' height='12'/></p></document>";
            var response = send(client, server.origin(), "resource", token, BindingJson.write(BindingJson.object(
                    "revision", before.get("revision"), "kind", "image", "name", "atomic_image", "bytes", png, "source", source, "data", data, "validateLayout", true)), null);
            assertEquals(200, response.statusCode(), response.body());
            var pasted = BindingJson.parse(response.body());
            assertEquals(source, pasted.get("source")); assertEquals(data, pasted.get("data"));
            assertTrue(((List<?>) pasted.get("images")).stream().anyMatch(value -> "atomic_image".equals(((Map<?, ?>) value).get("name"))));
            var undone = BindingJson.parse(send(client, server.origin(), "undo", token,
                    BindingJson.write(Map.of("revision", pasted.get("revision"))), null).body());
            assertEquals(original, undone.get("source")); assertEquals(data, undone.get("data"));
            assertEquals(before.get("resourceIdentity"), undone.get("resourceIdentity"));
            var redone = BindingJson.parse(send(client, server.origin(), "redo", token,
                    BindingJson.write(Map.of("revision", undone.get("revision"))), null).body());
            assertEquals(source, redone.get("source")); assertEquals(pasted.get("resourceIdentity"), redone.get("resourceIdentity"));
            for (String rejectedSource : List.of("<document version='1'><unsupported/></document>", "<!DOCTYPE document><document/>",
                    "<document version='1'><p line-height='1000'><image ref='rejected_image' width='12' height='12'/></p></document>")) {
                var rejected = send(client, server.origin(), "resource", token, BindingJson.write(BindingJson.object(
                        "revision", redone.get("revision"), "kind", "image", "name", "rejected_image", "bytes", png, "source", rejectedSource, "data", data, "validateLayout", true)), null);
                assertEquals(422, rejected.statusCode());
                var unchanged = BindingJson.parse(send(client, server.origin(), "state", token, null, null).body());
                assertEquals(redone.get("revision"), unchanged.get("revision"));
                assertEquals(source, unchanged.get("source")); assertEquals(redone.get("resourceIdentity"), unchanged.get("resourceIdentity"));
            }
        }
    }

    @Test @org.junit.jupiter.api.Timeout(30)
    void cancellingDuringImageUploadSurvivesQueuedStateRequestsAndPreservesTheWorkspace() throws Exception {
        try (var server = new DesignerApp.Server(0); var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            server.start();
            var home = client.send(HttpRequest.newBuilder(URI.create(server.origin() + "/")).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
            var match = Pattern.compile("name=\"designer-token\" content=\"([^\"]+)\"").matcher(home.body());
            assertTrue(match.find()); String token = match.group(1);
            var before = BindingJson.parse(send(client, server.origin(), "state", token, null, null).body());
            String source = "<document version='1'><p id='kept'>KEPT</p><p id='pasted'><image ref='cancelled_image' width='12' height='12'/></p></document>";
            byte[] body = BindingJson.write(BindingJson.object("revision", before.get("revision"), "kind", "image", "name", "cancelled_image",
                    "bytes", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a/QAAAABJRU5ErkJggg==",
                    "source", source, "data", "{}", "validateLayout", true)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var address = URI.create(server.origin());
            try (var socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", address.getPort()), 5_000); socket.setSoTimeout(10_000);
                String headers = "POST /api/resource HTTP/1.1\r\nHost: 127.0.0.1:" + address.getPort() + "\r\nX-Designer-Token: " + token
                        + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(headers.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                socket.getOutputStream().write(body, 0, 1); socket.getOutputStream().flush();
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (!readingRequestBody(server)) {
                    assertTrue(System.nanoTime() < deadline, "server must start reading the partial upload before cancellation");
                    Thread.yield();
                }
                var waiting = new ArrayList<CompletableFuture<HttpResponse<String>>>();
                for (int index = 0; index < 17; index++) {
                    waiting.add(client.sendAsync(HttpRequest.newBuilder(URI.create(server.origin() + "/api/state"))
                            .header("X-Designer-Token", token).timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString()));
                }
                // The first response proves that the bounded workspace queue is full while the upload still holds it.
                var overflow = (HttpResponse<?>) CompletableFuture.anyOf(waiting.toArray(CompletableFuture<?>[]::new)).get(5, TimeUnit.SECONDS);
                assertEquals(503, overflow.statusCode(), overflow.body().toString());
                var assets = List.of("/", "/studio.js", "/style.css").stream().map(path -> client.sendAsync(
                        HttpRequest.newBuilder(URI.create(server.origin() + path)).timeout(Duration.ofSeconds(2)).build(),
                        HttpResponse.BodyHandlers.discarding())).toList();
                for (var asset : assets) {
                    var response = asset.get(2, TimeUnit.SECONDS);
                    assertEquals(200, response.statusCode(), response.uri().toString());
                }
                assertEquals(403, send(client, server.origin(), "cancel", null, "{}", null).statusCode());
                assertEquals(403, send(client, server.origin(), "cancel", token, "{}", "https://foreign.invalid").statusCode());
                var cancel = client.send(HttpRequest.newBuilder(URI.create(server.origin() + "/api/cancel"))
                        .header("X-Designer-Token", token).timeout(Duration.ofSeconds(2))
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, cancel.statusCode(), cancel.body());
                socket.getOutputStream().write(body, 1, body.length - 1); socket.getOutputStream().flush();
                String response = new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(response.startsWith("HTTP/1.1 422"), response);
                int rejected = 0;
                for (var request : waiting) {
                    var result = request.get(5, TimeUnit.SECONDS);
                    if (result.statusCode() == 503) { rejected++; }
                    else { assertEquals(200, result.statusCode(), result.body()); }
                }
                assertEquals(1, rejected, "excess admission must fail explicitly without discarding admitted requests");
            }
            var after = BindingJson.parse(send(client, server.origin(), "state", token, null, null).body());
            for (String key : List.of("revision", "source", "data", "resourceIdentity", "canUndo", "canRedo", "historyResources")) {
                assertEquals(before.get(key), after.get(key), key);
            }
        }
    }

    private static boolean readingRequestBody(DesignerApp.Server server) {
        for (var thread : java.lang.management.ManagementFactory.getThreadMXBean().dumpAllThreads(true, false)) {
            if (java.util.Arrays.stream(thread.getLockedMonitors()).noneMatch(lock -> lock.getIdentityHashCode() == System.identityHashCode(server))) { continue; }
            for (var frame : thread.getStackTrace()) {
                if (frame.getClassName().equals(DesignerApp.Server.class.getName()) && frame.getMethodName().equals("read")) { return true; }
            }
        }
        return false;
    }

    private static HttpResponse<String> send(HttpClient client, String origin, String path, String token, String body, String foreignOrigin) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(origin + "/api/" + path)).timeout(Duration.ofSeconds(30));
        if (token != null) { builder.header("X-Designer-Token", token); }
        if (foreignOrigin != null) { builder.header("Origin", foreignOrigin); }
        if (body != null) { builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)); }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
