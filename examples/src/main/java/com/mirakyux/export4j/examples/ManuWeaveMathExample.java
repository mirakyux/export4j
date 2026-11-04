// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.docx.DocxMath;
import com.mirakyux.export4j.adapter.docx.DocxMathDocument;
import com.mirakyux.export4j.adapter.docx.DocxSemanticDocument;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.components.FlowColumn;
import com.mirakyux.export4j.components.FlowText;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.graphics.RgbColor;
import com.mirakyux.export4j.layout.core.ComponentBlock;
import com.mirakyux.export4j.model.Document;
import com.mirakyux.export4j.model.DocumentMetadata;
import com.mirakyux.export4j.model.PageMargins;
import com.mirakyux.export4j.model.PageSettings;
import com.mirakyux.export4j.model.PageSize;
import com.mirakyux.export4j.model.PaginationPolicy;
import com.mirakyux.export4j.model.ParagraphFormat;
import com.mirakyux.export4j.model.ParagraphAlignment;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.Section;
import com.mirakyux.export4j.model.SectionedDocument;
import com.mirakyux.export4j.text.ParagraphStyle;
import com.mirakyux.export4j.text.TextAlignment;
import com.mirakyux.export4j.text.TextDiagnostic;
import java.io.IOException;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.ArrayList;
import java.util.Optional;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/** Independent, self-authored display equations using the ordinary layout engine and vector backends. */
public final class ManuWeaveMathExample {
    private static final Path OUTPUT = Path.of("examples/build/manuweave-math");
    private static final double SIZE = 18;
    private static final List<Specimen> SPECIMENS = List.of(
            new Specimen("quadratic", "Fractions and radicals",
                    "x = \\frac{-b \\pm \\sqrt{b^2-4ac}}{2a}"),
            new Specimen("scripts", "Subscripts and superscripts",
                    "x_1^2 + x_2^2 = r^2"),
            new Specimen("integral", "Definite integral",
                    "\\int_{0}^{1} x^2\\,dx = \\frac{1}{3}"),
            new Specimen("sum", "Finite sum",
                    "\\sum_{i=1}^{n}i = \\frac{n(n+1)}{2}"),
            new Specimen("matrix", "Matrix with scalable delimiters",
                    "A = \\begin{pmatrix}a&b\\\\c&d\\end{pmatrix}"),
            new Specimen("cases", "Piecewise function",
                    "f(x) = \\begin{cases}x^2,&\\quad x\\geq 0\\\\-x,&\\quad x<0\\end{cases}"),
            new Specimen("greek-functions", "Greek symbols and trigonometric functions",
                    "\\sin^2 \\theta + \\cos^2 \\theta = 1 \\quad \\alpha + \\beta = \\gamma"),
            new Specimen("limit-product", "Limit and finite product",
                    "\\lim_{n \\to \\infty}\\frac{1}{n} = 0 \\qquad \\prod_{i=1}^{3}i = 6"));

    private ManuWeaveMathExample() { }

    public static void main(String[] args) throws IOException {
        if (args.length == 2 && args[0].equals("--verify-word-saved")) { verifyWordSaved(Path.of(args[1])); return; }
        if (args.length != 0) { throw new IllegalArgumentException("Expected no arguments or --verify-word-saved <path>"); }
        Files.createDirectories(OUTPUT);
        var fonts = FontGroup.strict(List.of(AuthoringExample.face("LiberationSans-Regular.ttf")));
        var engine = DocumentEngine.builder(fonts).build();
        var document = Document.builder(new DocumentMetadata("Display mathematics", "Export4j examples"),
                        new PageSettings(PageSize.A4, PageMargins.uniform(36)))
                .paragraph("math-title", "Display mathematics", ParagraphFormat.of(20))
                .paragraph("math-introduction", "Eight independent equations for scientific document specimens.",
                        ParagraphFormat.of(10));
        var report = new Properties();
        report.setProperty("formula.count", Integer.toString(SPECIMENS.size()));
        report.setProperty("math.library", "org.scilab.forge:jlatexmath:1.0.7");
        report.setProperty("math.fonts", "jlm_cmmi10,jlm_cmex10,jlm_cmr10,jlm_cmsy10");
        report.setProperty("math.output", "vector outlines with original TeX as logical text");
        report.setProperty("math.boundary", "Example-only offline subset; not MathML or editable Word equations");

        for (int index = 0; index < SPECIMENS.size(); index++) {
            var specimen = SPECIMENS.get(index);
            var formula = ManuWeaveMath.prepare(specimen.tex(), SIZE);
            String marker = String.format(Locale.ROOT, "MW-MATH-%02d", index + 1);
            var label = FlowText.builder(marker + "  " + specimen.label())
                    .style(new ParagraphStyle(10, 14, 0, 0, TextAlignment.LEFT)).color(RgbColor.BLACK).build();
            var row = FlowColumn.builder().gap(4).alignment(FlowColumn.Alignment.CENTER)
                    .add(label).add(formula).build();
            document.add(new ComponentBlock("math-" + specimen.id(),
                    ManuWeaveFlow.atomic(List.of(row), "math marker and equation"), 0, 12));
            String prefix = "formula." + (index + 1) + ".";
            report.setProperty(prefix + "id", specimen.id());
            report.setProperty(prefix + "marker", marker);
            report.setProperty(prefix + "tex", specimen.tex());
            report.setProperty(prefix + "fontSize", Double.toString(SIZE));
            report.setProperty(prefix + "width", Double.toString(formula.width()));
            report.setProperty(prefix + "height", Double.toString(formula.height()));
            report.setProperty(prefix + "baseline", Double.toString(formula.baseline()));
            report.setProperty(prefix + "paths", Integer.toString(formula.paths().size()));
            report.setProperty(prefix + "segments", Integer.toString(formula.paths().stream()
                    .mapToInt(path -> path.segments().size()).sum()));
        }
        var layout = engine.export(document.build()).backend(new PdfBackend()).to(OUTPUT.resolve("math.pdf"));
        if (layout.diagnostics().stream().anyMatch(value -> value.severity() == TextDiagnostic.Severity.WARNING)) {
            throw new IllegalStateException("Unexpected missing-glyph warning in mathematics specimen");
        }
        writeAtomically(OUTPUT.resolve("math.svg"), output -> new SvgBackend().write(layout.display(), output));
        report.setProperty("effective.pages", Integer.toString(layout.display().pages().size()));
        nativeWord(report);
        writeAtomically(OUTPUT.resolve("math-report.xml"), output -> report.storeToXML(output,
                "Original TeX and natural formula dimensions in points", StandardCharsets.UTF_8));
        System.out.println("ManuWeave mathematics: " + SPECIMENS.size() + " formulas; "
                + layout.display().pages().size() + " pages");
    }

    private static void verifyWordSaved(Path source) throws IOException {
        if (source.toAbsolutePath().normalize().equals(OUTPUT.resolve("word-readback.docx").toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("The verification input must differ from its reencoded output");
        }
        Files.createDirectories(OUTPUT); var adapter = new DocxAdapter(); var report = new Properties();
        try (var input = Files.newInputStream(source)) {
            adapter.readMath(input, DocxAdapter.Policy.STRICT);
            report.setProperty("word.strictSourceImport", "true");
        } catch (IOException failure) {
            report.setProperty("word.strictSourceImport", "false");
            report.setProperty("word.strictSourceBoundary", failure.getMessage());
        }
        DocxAdapter.MathReadResult imported;
        try (var input = Files.newInputStream(source)) { imported = adapter.readMath(input, DocxAdapter.Policy.ALLOW_LOSS); }
        var document = imported.document();
        if (document.equations().size() != 8
                || !(document.equations().getFirst().expression() instanceof DocxMath.Sequence sequence)
                || !(sequence.elements().getLast() instanceof DocxMath.Fraction fraction)
                || !(fraction.denominator() instanceof DocxMath.Run denominator) || !denominator.text().equals("5a")) {
            throw new IOException("The Word-saved specimen must retain eight native equations and the actual 5a denominator edit");
        }
        byte[] encoded = encodeWord(adapter, document);
        var reread = adapter.readMath(new ByteArrayInputStream(encoded), DocxAdapter.Policy.STRICT);
        if (!reread.diagnostics().isEmpty() || !document.equations().equals(reread.document().equations())
                || !document.settings().equals(reread.document().settings())) {
            throw new IOException("The Word-saved mathematical AST changed during strict reencoding/readback");
        }
        writeAtomically(OUTPUT.resolve("word-readback.docx"), output -> output.write(encoded));
        report.setProperty("word.sourceSha256", sha256(source));
        report.setProperty("word.readPolicy", "ALLOW_LOSS"); report.setProperty("word.nativeEquations", "8");
        report.setProperty("word.firstDenominator", "5a"); report.setProperty("word.mathAstPreserved", "true");
        report.setProperty("word.mathSettingsPreserved", "true"); report.setProperty("word.strictReencodedReadback", "true");
        report.setProperty("word.ordinaryImportBoundary", "Ordinary Word styles, theme/note parts and metadata use existing explicit loss diagnostics; mathematics remains strict");
        report.setProperty("word.diagnostics.count", Integer.toString(imported.diagnostics().size()));
        for (int index = 0; index < imported.diagnostics().size(); index++) {
            var diagnostic = imported.diagnostics().get(index); String prefix = "word.diagnostic." + (index + 1) + ".";
            report.setProperty(prefix + "part", diagnostic.part()); report.setProperty(prefix + "element", diagnostic.element());
            report.setProperty(prefix + "message", diagnostic.message());
        }
        writeAtomically(OUTPUT.resolve("word-readback-report.xml"), output -> report.storeToXML(output,
                "Installed Word save/edit readback; ordinary semantic losses are explicit", StandardCharsets.UTF_8));
        System.out.println("Word mathematics readback: 8 native equations, denominator 5a; strict AST/settings reencoding passed; "
                + imported.diagnostics().size() + " ordinary Word import diagnostics");
    }

    private static String sha256(Path source) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(source), digest)) { input.transferTo(OutputStream.nullOutputStream()); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("Java requires SHA-256", failure); }
    }

    private static void nativeWord(Properties report) throws IOException {
        var word = Document.builder(new DocumentMetadata("Editable display mathematics", "Export4j examples"),
                        new PageSettings(PageSize.A4, PageMargins.uniform(36)))
                .paragraph("word-math-title", "Editable display mathematics", ParagraphFormat.of(20));
        var expressions = wordExpressions(); var equations = new ArrayList<DocxMathDocument.Equation>();
        for (int index = 0; index < SPECIMENS.size(); index++) {
            String id = "native-equation-" + (index + 1); String marker = String.format(Locale.ROOT, "MW-MATH-%02d", index + 1);
            word.paragraph("word-label-" + index, marker + "  " + SPECIMENS.get(index).label(),
                    ParagraphFormat.of(10).withPagination(PaginationPolicy.builder().keepWithNext(true).build()));
            // A generous exact line box avoids clipping tall native fractions/matrices; Word paginates this file.
            word.add(new Paragraph(id, "\ufffc", new ParagraphFormat(18, 60, 0, 0, ParagraphAlignment.CENTER, 0, 8, true)));
            equations.add(new DocxMathDocument.Equation(id, new DocxSemanticDocument.Point(id, 0),
                    DocxMathDocument.Placement.DISPLAY_CENTER, expressions.get(index)));
        }
        var body = word.build();
        var source = new DocxMathDocument(new SectionedDocument(body.metadata(),
                List.of(new Section("native-mathematics", body.pageSettings(), body.blocks(), 0))), equations);
        var adapter = new DocxAdapter(); byte[] encoded = encodeWord(adapter, source);
        writeAtomically(OUTPUT.resolve("math.docx"), output -> output.write(encoded));
        byte[] standard = stripOwnMetadata(encoded); DocxMathDocument reopened = source;
        for (int round = 1; round <= 3; round++) {
            byte[] current = standard;
            writeAtomically(OUTPUT.resolve("math-standard-round-" + round + ".docx"), output -> output.write(current));
            var imported = adapter.readMath(new ByteArrayInputStream(current), DocxAdapter.Policy.STRICT);
            if (!imported.diagnostics().isEmpty() || !imported.document().equations().stream().map(DocxMathDocument.Equation::expression).toList().equals(expressions)
                    || !imported.document().settings().equals(source.settings())) {
                throw new IOException("standard native mathematics round trip changed semantics");
            }
            reopened = imported.document(); standard = stripOwnMetadata(encodeWord(adapter, reopened));
        }
        var first = (DocxMath.Sequence) reopened.equations().getFirst().expression();
        var fraction = (DocxMath.Fraction) first.elements().getLast();
        var replacement = DocxMath.sequence(plain("x = "), new DocxMath.Fraction(fraction.numerator(), run("3a")));
        var edited = reopened.withEquation(reopened.equations().getFirst().id(), replacement);
        byte[] editedBytes = encodeWord(adapter, edited);
        var verified = adapter.readMath(new ByteArrayInputStream(stripOwnMetadata(editedBytes)), DocxAdapter.Policy.STRICT);
        if (!verified.document().equations().getFirst().expression().equals(replacement)
                || !verified.document().equations().subList(1, 8).stream().map(DocxMathDocument.Equation::expression).toList().equals(expressions.subList(1, 8))) {
            throw new IOException("edited native equation did not survive standard OMML readback");
        }
        writeAtomically(OUTPUT.resolve("math-edited.docx"), output -> output.write(editedBytes));
        report.setProperty("word.formulas", Integer.toString(equations.size()));
        report.setProperty("word.standardRounds", "3"); report.setProperty("word.standardMetadataRemoved", "true");
        report.setProperty("word.editedEquation", "1"); report.setProperty("word.originalDenominator", "2a"); report.setProperty("word.editedDenominator", "3a");
        report.setProperty("word.nativeSemantics", "typed OMML AST; TeX is supplementary source only");
        report.setProperty("word.layout", "Native Word equations are editable and Word controls pagination; no PDF-layout equivalence is claimed");
    }

    private static List<DocxMath> wordExpressions() {
        var matrix = new DocxMath.Matrix(List.of(List.of(run("a"), run("b")), List.of(run("c"), run("d"))),
                List.of(DocxMath.ColumnAlignment.CENTER, DocxMath.ColumnAlignment.CENTER), DocxMath.BaseAlignment.CENTER);
        var cases = new DocxMath.Matrix(List.of(List.of(DocxMath.sequence(new DocxMath.Superscript(run("x"), run("2")), plain(",")), run("x ≥ 0")), List.of(run("−x,"), run("x < 0"))),
                List.of(DocxMath.ColumnAlignment.LEFT, DocxMath.ColumnAlignment.LEFT), DocxMath.BaseAlignment.CENTER);
        return List.of(
                DocxMath.sequence(plain("x = "), new DocxMath.Fraction(DocxMath.sequence(run("−b"), plain(" ± "), new DocxMath.Radical(DocxMath.sequence(new DocxMath.Superscript(run("b"), run("2")), run("−4ac")))), run("2a"))),
                DocxMath.sequence(new DocxMath.SubSuperscript(run("x"), run("1"), run("2")), plain(" + "), new DocxMath.SubSuperscript(run("x"), run("2"), run("2")), plain(" = "), new DocxMath.Superscript(run("r"), run("2"))),
                DocxMath.sequence(new DocxMath.Nary(DocxMath.Operator.INTEGRAL, Optional.of(run("0")), Optional.of(run("1")), DocxMath.sequence(new DocxMath.Superscript(run("x"), run("2")), run(" dx")), DocxMath.LimitLocation.SUB_SUP, true), plain(" = "), new DocxMath.Fraction(run("1"), run("3"))),
                DocxMath.sequence(new DocxMath.Nary(DocxMath.Operator.SUM, Optional.of(run("i=1")), Optional.of(run("n")), run("i"), DocxMath.LimitLocation.UNDER_OVER, true), plain(" = "), new DocxMath.Fraction(run("n(n+1)"), run("2"))),
                DocxMath.sequence(plain("A = "), new DocxMath.Delimiter("(", ")", matrix)),
                DocxMath.sequence(plain("f(x) = "), new DocxMath.Delimiter("{", "", cases)),
                DocxMath.sequence(new DocxMath.Function(new DocxMath.Superscript(plain("sin"), run("2")), run("θ")), plain(" + "), new DocxMath.Function(new DocxMath.Superscript(plain("cos"), run("2")), run("θ")), plain(" = 1    "), run("α + β = γ")),
                DocxMath.sequence(new DocxMath.LowerLimit(plain("lim"), run("n→∞")), new DocxMath.Fraction(run("1"), run("n")), plain(" = 0    "), new DocxMath.Nary(DocxMath.Operator.PRODUCT, Optional.of(run("i=1")), Optional.of(run("3")), run("i"), DocxMath.LimitLocation.UNDER_OVER, true), plain(" = 6")));
    }

    private static DocxMath run(String value) { return new DocxMath.Run(value); }
    private static DocxMath plain(String value) { return new DocxMath.Run(value, DocxMath.Style.PLAIN); }
    private static byte[] encodeWord(DocxAdapter adapter, DocxMathDocument document) throws IOException {
        var encoded = new ByteArrayOutputStream(); adapter.writeMath(document, encoded); return encoded.toByteArray();
    }

    /** Standard-input control: removes only this project's optional metadata from its freshly generated package. */
    private static byte[] stripOwnMetadata(byte[] encoded) throws IOException {
        String own = "urn:com.mirakyux.export4j:semantic:1"; String mc = "http://schemas.openxmlformats.org/markup-compatibility/2006";
        try {
            var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true); factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var transforms = TransformerFactory.newInstance(); transforms.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            transforms.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); transforms.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var output = new ByteArrayOutputStream();
            try (var input = new ZipInputStream(new ByteArrayInputStream(encoded)); var zip = new ZipOutputStream(output)) {
                for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                    byte[] value = input.readAllBytes();
                    if (entry.getName().endsWith(".xml") || entry.getName().endsWith(".rels")) {
                        var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(value));
                        var elements = document.getElementsByTagName("*");
                        for (int index = 0; index < elements.getLength(); index++) {
                            var element = (org.w3c.dom.Element) elements.item(index); var attributes = element.getAttributes();
                            for (int attribute = attributes.getLength() - 1; attribute >= 0; attribute--) {
                                var item = attributes.item(attribute);
                                if (own.equals(item.getNamespaceURI()) || XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(item.getNamespaceURI()) && own.equals(item.getNodeValue())) { element.removeAttributeNode((org.w3c.dom.Attr) item); }
                            }
                            if (element.hasAttributeNS(mc, "Ignorable")) {
                                var prefixes = List.of(element.getAttributeNS(mc, "Ignorable").split("\\s+")).stream().filter(prefix -> !prefix.equals("od")).toList();
                                if (prefixes.isEmpty()) { element.removeAttributeNS(mc, "Ignorable"); } else { element.setAttributeNS(mc, "mc:Ignorable", String.join(" ", prefixes)); }
                            }
                        }
                        var changed = new ByteArrayOutputStream(); transforms.newTransformer().transform(new DOMSource(document), new StreamResult(changed)); value = changed.toByteArray();
                    }
                    var fresh = new ZipEntry(entry.getName()); fresh.setTime(0); zip.putNextEntry(fresh); zip.write(value); zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException | javax.xml.transform.TransformerException failure) {
            throw new IOException("cannot prepare standard native mathematics input", failure);
        }
    }

    private static void writeAtomically(Path target, OutputWriter writer) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path temporary = Files.createTempFile(absolute.getParent(), ".manuweave-math-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) { writer.write(output); }
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @FunctionalInterface
    private interface OutputWriter { void write(OutputStream output) throws IOException; }

    private record Specimen(String id, String label, String tex) { }
}
