// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.docx.DocxMath;
import com.mirakyux.export4j.adapter.docx.DocxMathDocument;
import com.mirakyux.export4j.adapter.docx.DocxSemanticDocument;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

/** Published public API acceptance; no example helpers, TeX library or Office application. */
public final class NativeMathExample {
    private NativeMathExample() { }

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
        if (args.length != 0) { throw new IllegalArgumentException("No arguments expected"); }
        Path output = Path.of(System.getProperty("consumer.outputDirectory", "build/output"));
        Files.createDirectories(output);
        var fraction = new DocxMath.Fraction(DocxMath.run("1"), DocxMath.run("2a"));
        var matrix = new DocxMath.Matrix(List.of(List.of(DocxMath.run("a"), DocxMath.run("b")),
                List.of(DocxMath.run("c"), DocxMath.run("d"))),
                List.of(DocxMath.ColumnAlignment.CENTER, DocxMath.ColumnAlignment.CENTER), DocxMath.BaseAlignment.CENTER);
        var body = new SectionedDocument(DocumentMetadata.EMPTY, List.of(new Section("main", PageSettings.A4,
                List.of(new Paragraph("fraction-anchor", "\ufffc", ParagraphFormat.of(18)),
                        new Paragraph("matrix-anchor", "\ufffc", ParagraphFormat.of(18))), 0)));
        var source = new DocxMathDocument(body, List.of(
                equation("fraction", "fraction-anchor", fraction),
                equation("matrix", "matrix-anchor", new DocxMath.Delimiter("(", ")", matrix))));
        var adapter = new DocxAdapter(); byte[] original = write(adapter, source);
        var imported = adapter.readMath(new ByteArrayInputStream(original), DocxAdapter.Policy.STRICT);
        if (!imported.diagnostics().isEmpty() || !source.equations().equals(imported.document().equations())
                || !source.settings().equals(imported.document().settings())) { throw new IOException("Published native math roundtrip changed the typed document"); }
        var replacement = new DocxMath.Fraction(DocxMath.run("1"), DocxMath.run("3a"));
        var edited = imported.document().withEquation("fraction", replacement); byte[] encoded = write(adapter, edited);
        var reread = adapter.readMath(new ByteArrayInputStream(encoded), DocxAdapter.Policy.STRICT);
        if (!reread.diagnostics().isEmpty() || !edited.equations().equals(reread.document().equations())
                || !replacement.equals(reread.document().equations().getFirst().expression())
                || !source.equations().get(1).equals(reread.document().equations().get(1))
                || !fraction.equals(source.equations().getFirst().expression())) { throw new IOException("Published native math edit changed another equation or its source"); }
        for (var policy : DocxAdapter.Policy.values()) {
            try { adapter.read(new ByteArrayInputStream(original), policy); throw new IllegalStateException("Portable import silently accepted native mathematics"); }
            catch (IOException expected) { /* The portable model cannot represent native mathematics. */ }
            try { adapter.readSemantic(new ByteArrayInputStream(original), policy); throw new IllegalStateException("Semantic import silently accepted native mathematics"); }
            catch (IOException expected) { /* The dedicated typed API is required. */ }
        }
        Files.write(output.resolve("consumer-native-math.docx"), original);
        Files.write(output.resolve("consumer-native-math-edited.docx"), encoded);
        var report = new Properties(); report.setProperty("formulas", "2");
        report.setProperty("strictRoundtrip", "true"); report.setProperty("actualEquationEdit", "true");
        report.setProperty("otherEquationUnchanged", "true"); report.setProperty("sourceUnchanged", "true");
        report.setProperty("originalDenominator", "2a"); report.setProperty("editedDenominator", "3a");
        report.setProperty("portableReadersRejectMath", "true");
        var digest = MessageDigest.getInstance("SHA-256");
        report.setProperty("originalSha256", HexFormat.of().formatHex(digest.digest(original)));
        report.setProperty("editedSha256", HexFormat.of().formatHex(digest.digest(encoded)));
        try (var stream = Files.newOutputStream(output.resolve("consumer-native-math-report.xml"))) {
            report.storeToXML(stream, "Published native DOCX math public API acceptance");
        }
        System.out.println("Published native mathematics: fraction + matrix; strict roundtrip and 2a -> 3a edit passed");
    }

    private static DocxMathDocument.Equation equation(String id, String paragraph, DocxMath expression) {
        return new DocxMathDocument.Equation(id, new DocxSemanticDocument.Point(paragraph, 0),
                DocxMathDocument.Placement.DISPLAY_CENTER, expression);
    }
    private static byte[] write(DocxAdapter adapter, DocxMathDocument source) throws IOException {
        var output = new ByteArrayOutputStream(); adapter.writeMath(source, output); return output.toByteArray();
    }
}
