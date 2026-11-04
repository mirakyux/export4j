// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.graphics.DisplayDocument;
import com.mirakyux.export4j.text.TextDiagnostic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runs all attributed ManuWeave formatting specimens offline, or selects explicit profile IDs. */
public final class ManuWeaveExample {
    private static final Path OUTPUT = Path.of("examples/build/manuweave");

    private ManuWeaveExample() { }

    public static void main(String[] args) throws IOException {
        Map<String, String> catalog = catalog();
        var arguments = arguments(args);
        if (arguments.list()) {
            catalog.values().forEach(System.out::println);
            return;
        }
        List<String> selected = arguments.ids().isEmpty() ? List.copyOf(catalog.keySet()) : arguments.ids();
        if (selected.size() != selected.stream().distinct().count()) {
            throw new IllegalArgumentException("Select each ManuWeave profile only once");
        }
        var profiles = new ArrayList<ManuWeaveProfile>();
        for (String id : selected) {
            if (!catalog.containsKey(id)) {
                throw new IllegalArgumentException("Unknown ManuWeave profile: " + id + "; use --list");
            }
            profiles.add(ManuWeaveProfile.load(id));
        }
        var fonts = new ManuWeaveFonts(arguments.fontMap());
        Files.createDirectories(OUTPUT);
        for (var profile : profiles) {
            for (String role : profile.roles()) { profile.style(role, fonts); }
            var sample = new ManuWeaveSample(profile, fonts);
            var document = sample.document();
            var builder = DocumentEngine.builder(fonts.group(false, false, false));
            ManuWeaveRunning.fontRoles(profile, fonts).forEach(builder::fontRole);
            var layout = builder.build().layout(document);
            if (layout.diagnostics().stream().anyMatch(value -> value.severity() == TextDiagnostic.Severity.WARNING)) {
                throw new IllegalStateException("Unexpected missing-glyph warning: " + profile.id);
            }
            var display = ManuWeaveReview.decorate(profile, fonts, layout, document);
            writePdfAtomically(OUTPUT.resolve(profile.id + ".pdf"), display);
            profile.report.setProperty("effective.pages", "" + layout.display().pages().size());
            sample.report();
            try (var output = Files.newOutputStream(OUTPUT.resolve(profile.id + "-report.xml"))) {
                profile.report.storeToXML(output, "Export4j example projection and explicit differences", StandardCharsets.UTF_8);
            }
            Files.writeString(OUTPUT.resolve(profile.id + "-source.txt"), sample.source(), StandardCharsets.UTF_8);
            System.out.println(profile.id + ": " + layout.display().pages().size() + " pages; "
                    + profile.frontColumns + "/" + profile.bodyColumns + " columns");
        }
    }

    private static Arguments arguments(String[] args) {
        var ids = new ArrayList<String>();
        Path fontMap = null;
        boolean list = false;
        for (int index = 0; index < args.length; index++) {
            if (args[index].equals("--font-map")) {
                if (fontMap != null || index + 1 == args.length || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException("--font-map requires one explicit Properties XML path");
                }
                fontMap = Path.of(args[++index]);
            } else if (args[index].equals("--list")) {
                if (list) { throw new IllegalArgumentException("Duplicate --list"); }
                list = true;
            } else {
                ids.add(args[index]);
            }
        }
        if (list && (!ids.isEmpty() || fontMap != null)) {
            throw new IllegalArgumentException("Use --list alone");
        }
        return new Arguments(List.copyOf(ids), fontMap, list);
    }

    private static void writePdfAtomically(Path target, DisplayDocument document) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path temporary = Files.createTempFile(absolute.getParent(), ".manuweave-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) { new PdfBackend().write(document, output); }
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException | Error failure) {
            try { Files.deleteIfExists(temporary); }
            catch (IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private record Arguments(List<String> ids, Path fontMap, boolean list) { }

    private static Map<String, String> catalog() throws IOException {
        String source;
        try (var input = ManuWeaveExample.class.getResourceAsStream("/templates/manuweave/index.tsv")) {
            if (input == null) { throw new IOException("Missing ManuWeave catalog"); }
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var values = new LinkedHashMap<String, String>();
        for (String row : source.lines().toList()) {
            String[] fields = row.split("\t", -1);
            if (fields.length != 5 || !fields[1].matches("[a-z0-9-]+") || values.putIfAbsent(fields[1], row) != null) {
                throw new IOException("Malformed ManuWeave catalog row");
            }
        }
        if (values.size() != 286) { throw new IOException("Expected the pinned README's 286 profile IDs"); }
        return values;
    }
}
