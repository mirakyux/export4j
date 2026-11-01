// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.text.TextDiagnostic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Offline business templates with fictional data, explicit fonts and credited sample images. */
public final class CommonDocumentsExample {
    private static final Path OUTPUT = Path.of("examples/build/common-documents");
    private static final List<String> IDS = List.of(
            "service-contract", "resume", "user-manual", "quotation", "meeting-minutes", "weekly-report");

    private CommonDocumentsExample() { }

    public static void main(String[] args) throws IOException {
        var selected = args.length == 0 ? IDS : List.of(args);
        for (String id : selected) {
            if (!IDS.contains(id)) { throw new IllegalArgumentException("Choose from " + IDS + ": " + id); }
        }
        Files.createDirectories(OUTPUT);
        var engine = AdrReportData.engine("LXGWWenKaiTC-Regular.ttf");
        for (String id : selected) {
            var layout = engine.export(document(id, data(id))).backend(new PdfBackend()).to(OUTPUT.resolve(id + ".pdf"));
            // Chinese text uses the explicitly configured fallback font; replacement warnings still fail.
            layout.contextualDiagnostics().stream()
                    .filter(value -> value.diagnostic().severity() == TextDiagnostic.Severity.WARNING)
                    .findFirst().ifPresent(value -> { throw new IllegalStateException(id + " warning: " + value); });
            Files.writeString(OUTPUT.resolve(id + ".xml"), resource(id, ".xml"), StandardCharsets.UTF_8);
            Files.writeString(OUTPUT.resolve(id + ".json"), resource(id, ".json"), StandardCharsets.UTF_8);
            for (String image : images(id)) {
                Files.createDirectories(OUTPUT.resolve("images"));
                Files.createDirectories(OUTPUT.resolve("licenses"));
                Files.write(OUTPUT.resolve("images/" + image + ".png"), resourceBytes("assets/" + image + ".png"));
                Files.write(OUTPUT.resolve("licenses/" + image + "-license.txt"),
                        resourceBytes("assets/" + image + "-license.txt"));
            }
            System.out.println(id + ": " + layout.display().pages().size() + " pages");
        }
    }

    static SectionedDocument document(String id, Map<String, Object> data) throws IOException {
        var compiler = XmlTemplate.builder();
        for (String image : images(id)) {
            compiler.image(image, ImageResource.load(resourceBytes("assets/" + image + ".png")));
        }
        return compiler.build().compile(resource(id, ".xml")).renderSections(data);
    }

    private static List<String> images(String id) {
        return switch (id) {
            case "resume" -> List.of("resume_portrait");
            case "service-contract" -> List.of("contract_logo");
            default -> List.of();
        };
    }

    static Map<String, Object> data(String id) {
        return switch (id) {
            case "service-contract", "resume", "user-manual" -> PersonalDocumentData.data(id);
            case "quotation", "meeting-minutes", "weekly-report" -> OfficeDocumentData.data(id);
            default -> throw new IllegalArgumentException("Unknown document example: " + id);
        };
    }

    private static String resource(String id, String extension) throws IOException {
        return new String(resourceBytes(id + extension), StandardCharsets.UTF_8);
    }

    private static byte[] resourceBytes(String name) throws IOException {
        try (var input = CommonDocumentsExample.class.getResourceAsStream("/templates/common/" + name)) {
            if (input == null) { throw new IOException("Missing common document resource: " + name); }
            return input.readAllBytes();
        }
    }
}
