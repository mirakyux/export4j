// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.designer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class DesignerHistoryRestoreTest {
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a/QAAAABJRU5ErkJggg==";
    private static final String DATA = "{\"name\":\"Ada\",\"exact\":9007199254740993}";

    // Compilation cannot detect replay appends, lost redo resources, or mutations after a rejected restore.
    @Test void exactRestorePreservesTwoResourcesAndTheirTextAcrossRepeatedUndoAndRedo() throws Exception {
        try (var workspace = new Workspace()) {
            var original = workspace.apply("Original");
            var firstResource = workspace.image("first_image");
            var firstText = workspace.apply("First");
            var secondResource = workspace.image("second_image");
            var secondText = workspace.apply("Second");
            var targets = List.of(secondResource, firstText, firstResource, original, firstResource, firstText, secondResource, secondText);
            for (int repeat = 0; repeat < 2; repeat++) {
                for (var target : targets) { assertProject(target, workspace.restore(target)); }
            }
            workspace.restore(firstText);
            assertEquals(Boolean.TRUE, workspace.state.get("canRedo"));
            var uncommitted = new LinkedHashMap<>(firstText);
            uncommitted.put("source", source("Intermediate that was never committed"));
            assertProject(uncommitted, workspace.restore(uncommitted));
            assertEquals(Boolean.TRUE, workspace.state.get("canRedo"), "source replay must retain future resource snapshots");
            assertProject(secondText, workspace.restore(secondText));
            assertEquals(List.of("first_image", "second_image"), images(workspace.state));
        }
    }

    @Test void unknownResourcesInvalidCandidatesAndStaleRevisionsLeaveTheWorkspaceUnchanged() throws Exception {
        try (var workspace = new Workspace()) {
            workspace.apply("Kept"); workspace.image("kept_image");
            var before = workspace.read();
            var missing = new LinkedHashMap<>(before); missing.put("resourceIdentity", "0".repeat(64));
            assertEquals(409, workspace.post("restore", missing).statusCode()); assertEquals(before, workspace.read());
            for (String invalid : List.of("<document version='1'><unsupported/></document>", "<!DOCTYPE document><document/>")) {
                var candidate = new LinkedHashMap<>(before); candidate.put("source", invalid);
                assertEquals(422, workspace.post("restore", candidate).statusCode()); assertEquals(before, workspace.read());
            }
            var invalidData = new LinkedHashMap<>(before); invalidData.put("data", "{");
            assertEquals(422, workspace.post("restore", invalidData).statusCode()); assertEquals(before, workspace.read());
            var stale = new LinkedHashMap<>(before); stale.put("revision", 0);
            assertEquals(409, workspace.post("restore", stale).statusCode()); assertEquals(before, workspace.read());
        }
    }

    @Test void resourceHistoryExpiryIsExposedAndRejectedAtomicallyWithinTheExistingBudget() throws Exception {
        try (var workspace = new Workspace()) {
            var original = workspace.apply("Original");
            workspace.image("retained_image");
            for (int index = 0; index < 9; index++) { workspace.apply("Later " + index); }
            var before = workspace.read();
            assertFalse(((List<?>) before.get("historyResources")).contains(original.get("resourceIdentity")));
            assertTrue(((List<?>) before.get("historyResources")).contains(before.get("resourceIdentity")));
            var expired = new LinkedHashMap<>(original); expired.remove("revision");
            var rejected = workspace.post("restore", expired);
            assertEquals(409, rejected.statusCode()); assertTrue(rejected.body().contains("资源撤销历史"));
            assertEquals(before, workspace.read());
        }
    }

    @Test void fontIdentityTracksFontReplacementAndUndoWithoutReloadingAfterTextOrImageEdits() throws Exception {
        try (var workspace = new Workspace()) {
            var original = workspace.apply("Original");
            Object fonts = original.get("fontIdentity");
            assertTrue(fonts instanceof String && !((String) fonts).isBlank());
            assertEquals(fonts, workspace.apply("Edited").get("fontIdentity"));
            var image = workspace.image("extra_image");
            assertNotEquals(original.get("resourceIdentity"), image.get("resourceIdentity"));
            assertEquals(fonts, image.get("fontIdentity"));
            var replacement = workspace.accept("resource", Map.of("kind", "font", "name", "Regular.ttf", "license", "Fixture license",
                    "bytes", java.util.Base64.getEncoder().encodeToString(DesignerService.resource("LiberationSans-Bold.ttf"))));
            assertNotEquals(fonts, replacement.get("fontIdentity"));
            assertEquals(fonts, workspace.restore(image).get("fontIdentity"));
            assertEquals(replacement.get("fontIdentity"), workspace.restore(replacement).get("fontIdentity"));
        }
    }

    private static String source(String text) { return "<document version='1'><p id='text'>" + text + "</p></document>"; }
    private static List<String> images(Map<String, Object> state) {
        var names = new ArrayList<String>();
        for (Object image : (List<?>) state.get("images")) { names.add((String) ((Map<?, ?>) image).get("name")); }
        return names.stream().sorted().toList();
    }
    private static void assertProject(Map<String, Object> expected, Map<String, Object> actual) {
        for (String key : List.of("source", "data", "resourceIdentity", "fontIdentity", "fonts", "images", "fragments", "licenses")) {
            assertEquals(expected.get(key), actual.get(key), key);
        }
    }

    private static final class Workspace implements AutoCloseable {
        private final DesignerApp.Server server;
        private final HttpClient client;
        private final String token;
        private Map<String, Object> state;
        Workspace() throws Exception {
            server = new DesignerApp.Server(0);
            client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            try {
                server.start();
                var home = client.send(HttpRequest.newBuilder(URI.create(server.origin() + "/")).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
                var match = Pattern.compile("name=\"designer-token\" content=\"([^\"]+)\"").matcher(home.body());
                assertTrue(match.find()); token = match.group(1);
                state = read(); accept("new", Map.of("kind", "blank"));
            } catch (Exception | Error failure) {
                client.close(); server.close(); throw failure;
            }
        }
        Map<String, Object> read() throws Exception {
            var response = client.send(HttpRequest.newBuilder(URI.create(server.origin() + "/api/state"))
                    .header("X-Designer-Token", token).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body()); return BindingJson.parse(response.body());
        }
        HttpResponse<String> post(String route, Map<String, Object> values) throws Exception {
            var payload = new LinkedHashMap<String, Object>(); payload.put("revision", state.get("revision")); payload.putAll(values);
            return client.send(HttpRequest.newBuilder(URI.create(server.origin() + "/api/" + route))
                    .header("X-Designer-Token", token).header("Content-Type", "application/json").timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(BindingJson.write(payload))).build(), HttpResponse.BodyHandlers.ofString());
        }
        Map<String, Object> accept(String route, Map<String, Object> values) throws Exception {
            var response = post(route, values); assertEquals(200, response.statusCode(), response.body());
            state = BindingJson.parse(response.body()); return state;
        }
        Map<String, Object> apply(String text) throws Exception { return accept("apply", Map.of("source", source(text), "data", DATA)); }
        Map<String, Object> image(String name) throws Exception { return accept("resource", Map.of("kind", "image", "name", name, "bytes", PNG)); }
        Map<String, Object> restore(Map<String, Object> target) throws Exception {
            return accept("restore", Map.of("source", target.get("source"), "data", target.get("data"), "resourceIdentity", target.get("resourceIdentity")));
        }
        @Override public void close() { client.close(); server.close(); }
    }
}
