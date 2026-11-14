// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j;

import com.mirakyux.export4j.font.FontSource;
import com.mirakyux.export4j.model.DocumentTemplate;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Object data must retain the starter's output and per-operation snapshot contracts. */
public class Export4jObjectBindingTest {
    private static final String XML = """
            <document version="1" content-format="MARKDOWN" formulas="true">
              <p>${customer.name} - ${attributes.period}</p>
              <each items="items" as="item">
                <p>${item.label}: ${item.quantity}</p>
              </each>
              <content id="formula" source="attributes.detail"/>
            </document>
            """;

    @TempDir Path directory;

    @Test void recordsBeansAndMixedContainersProduceTheSamePdfAndDocxAsMaps() throws Exception {
        var attributes = Map.of("period", "Q4", "detail", "Formula $x^2$.");
        var mapData = Map.of("customer", Map.of("name", "Reader"),
                "items", List.of(Map.of("label", "Value", "quantity", 3)), "attributes", attributes);
        var recordData = new TemplateData(new CustomerBean("Reader"),
                List.of(new Item("Value", 3)), attributes);
        var beanData = new TemplateBean(new Customer("Reader"),
                List.of(Map.of("label", "Value", "quantity", 3)), attributes);
        var mixedData = Map.of("customer", new CustomerBean("Reader"),
                "items", List.of(new Item("Value", 3)), "attributes", attributes);

        try (var exporter = Export4j.builder(latin()).build()) {
            var expected = export(exporter.xml(XML, mapData));
            assertTrue(expected.pdfText().contains("Reader - Q4"));
            assertTrue(expected.pdfText().contains("Value: 3"));
            assertTrue(new String(expected.documentXml(), StandardCharsets.UTF_8).contains("oMath"));
            for (Object data : List.of(recordData, beanData, mixedData)) {
                var actual = export(exporter.xml(XML, data));
                assertEquals(expected.pdfText(), actual.pdfText(), data.getClass().getName());
                assertArrayEquals(expected.documentXml(), actual.documentXml(), data.getClass().getName());
            }
        }
    }

    @Test void eachTargetSnapshotsBeanValuesWhenThatTargetExecutes() throws Exception {
        var data = new MutableBody("Before request");
        try (var exporter = Export4j.builder(latin()).build()) {
            var request = exporter.xml("<document version='1'><p>${body}</p></document>", data);
            assertEquals(0, data.reads);
            data.body = "First execution";
            var pdf = new ByteArrayOutputStream();
            request.pdf(pdf);
            assertTrue(pdfText(pdf.toByteArray()).contains("First execution"));
            assertEquals(1, data.reads);

            data.body = "Second execution";
            var docx = new ByteArrayOutputStream();
            request.docx(docx);
            String document = new String(documentXml(docx.toByteArray()), StandardCharsets.UTF_8);
            assertTrue(document.contains("Second execution"));
            assertFalse(document.contains("First execution"));
            assertEquals(2, data.reads);
        }
    }

    @Test void getterFailureRetainsOldTargetsAndLeavesNoTemporaryFiles() throws Exception {
        byte[] old = "previous document".getBytes(StandardCharsets.UTF_8);
        try (var exporter = Export4j.builder(latin()).build()) {
            var request = exporter.xml("<document version='1'><p>${body}</p></document>", new FailingBody());
            for (String extension : List.of("pdf", "docx")) {
                Path target = directory.resolve("document." + extension);
                Files.write(target, old);
                var failure = assertThrows(DocumentTemplate.BindingException.class, () -> {
                    if (extension.equals("pdf")) { request.pdf(target); }
                    else { request.docx(target); }
                });
                assertEquals(DocumentTemplate.BindingException.Code.INVALID_VALUE, failure.code());
                assertNull(failure.getCause());
                assertFalse(failure.getMessage().contains("private getter detail"));
                assertArrayEquals(old, Files.readAllBytes(target));
            }
        }
        try (var files = Files.list(directory)) {
            assertEquals(List.of("document.docx", "document.pdf"),
                    files.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    private static Output export(Export4j.Request request) throws Exception {
        var pdf = new ByteArrayOutputStream();
        assertEquals(1, request.pdf(pdf).formulas());
        var docx = new ByteArrayOutputStream();
        assertEquals(1, request.docx(docx).formulas());
        return new Output(pdfText(pdf.toByteArray()), documentXml(docx.toByteArray()));
    }

    private static String pdfText(byte[] bytes) throws IOException {
        try (var document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static byte[] documentXml(byte[] bytes) throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("word/document.xml")) { return zip.readAllBytes(); }
            }
        }
        throw new IOException("missing word/document.xml");
    }

    private static FontFamily latin() {
        return FontFamily.builder("Liberation Sans", source("LiberationSans-Regular.ttf"))
                .bold(source("LiberationSans-Bold.ttf"))
                .italic(source("LiberationSans-Italic.ttf"))
                .boldItalic(source("LiberationSans-BoldItalic.ttf"))
                .build();
    }

    private static FontSource source(String name) {
        return FontSource.resource(Export4jObjectBindingTest.class, "/fonts/" + name);
    }

    private record Output(String pdfText, byte[] documentXml) { }

    public record Customer(String name) { }
    public record Item(String label, int quantity) { }
    public record TemplateData(Object customer, List<?> items, Map<String, ?> attributes) { }

    public static final class CustomerBean {
        private final String name;
        public CustomerBean(String name) { this.name = name; }
        public String getName() { return name; }
    }

    public static final class TemplateBean {
        private final Object customer;
        private final List<?> items;
        private final Map<String, ?> attributes;
        public TemplateBean(Object customer, List<?> items, Map<String, ?> attributes) {
            this.customer = customer;
            this.items = items;
            this.attributes = attributes;
        }
        public Object getCustomer() { return customer; }
        public List<?> getItems() { return items; }
        public Map<String, ?> getAttributes() { return attributes; }
    }

    public static final class MutableBody {
        private String body;
        private int reads;
        public MutableBody(String body) { this.body = body; }
        public String getBody() { reads++; return body; }
    }

    public static final class FailingBody {
        public String getBody() { throw new IllegalStateException("private getter detail"); }
    }
}
