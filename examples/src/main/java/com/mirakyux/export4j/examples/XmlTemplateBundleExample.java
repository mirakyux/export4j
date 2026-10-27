// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.examples;

import com.mirakyux.export4j.adapter.docx.DocxAdapter;
import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.ImageResource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** XML source editing, ordinary ZIP resources and existing data binding, without model serialization. */
public final class XmlTemplateBundleExample {
    private static final Path OUTPUT = Path.of("examples/build/xml-template-bundle");
    private static final String SOURCE = """
            <document version="1" title="Template ${title}">
              <page width="420" height="500" margin="28"/>
              <heading level="1" size="20" line-height="28">XML-TEMPLATE ${title}</heading>
              <include ref="intro" prefix="intro"/>
              <if test="approved"><then><p>STATUS-APPROVED</p></then><else><p>STATUS-PENDING</p></else></if>
              <each items="items" as="item"><p>ITEM ${item.name}</p></each>
              <rows items="items" as="item"><columns><column weight="3"/><column weight="1"/></columns>
                <header><cell>Name</cell><cell>Count</cell></header>
                <row><cell>${item.name}</cell><cell>${item.count}</cell></row>
              </rows>
              <p line-height="32"><image ref="logo" width="48" height="24" alt="Synthetic blue logo"/></p>
              <p>END-TEMPLATE</p>
            </document>
            """;
    private static final String INTRO = "<fragment version='1'><p>ORIGINAL-INTRO ${title}</p></fragment>\r\n";
    private XmlTemplateBundleExample() { }

    public static void main(String[] args) throws IOException {
        Files.createDirectories(OUTPUT);
        byte[] font;
        try (var input = XmlTemplateBundleExample.class.getResourceAsStream("/fonts/LiberationSans-Regular.ttf")) {
            if (input == null) { throw new IOException("example font missing"); } font = input.readAllBytes();
        }
        var image = new BufferedImage(48, 24, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try { graphics.setColor(new java.awt.Color(0x2255aa)); graphics.fillRect(0, 0, 48, 24); }
        finally { graphics.dispose(); }
        var png = new ByteArrayOutputStream(); ImageIO.write(image, "png", png);
        var original = XmlTemplateBundle.builder(SOURCE).fragment("intro", INTRO)
                .image("logo", ImageResource.load(png.toByteArray())).font("Regular.ttf", font)
                .license("LiberationSans-OFL.txt", Files.readString(Path.of("assets/fonts/LiberationSans-OFL.txt"))).build();
        original.write(OUTPUT.resolve("original.zip"));
        var reopened = XmlTemplateBundle.read(Files.readAllBytes(OUTPUT.resolve("original.zip")));
        var edited = reopened.toBuilder().source(reopened.source().replace("XML-TEMPLATE", "EDITED-TEMPLATE"))
                .fragment("intro", reopened.fragments().get("intro").replace("ORIGINAL-INTRO", "EDITED-INTRO")).build();
        edited.write(OUTPUT.resolve("edited.zip"));
        var finalTemplate = XmlTemplateBundle.read(Files.readAllBytes(OUTPUT.resolve("edited.zip")));
        render(reopened, "original", "Batch-A", true);
        render(finalTemplate, "edited", "Batch-A", true);
        render(finalTemplate, "alternate", "Batch-B", false);
        System.out.println("XML template sources saved, edited, reopened and bound to two datasets: 3 PDF/DOCX pairs");
    }

    private static void render(XmlTemplateBundle bundle, String name, String title, boolean approved) throws IOException {
        var face = TrueTypeFontLoader.load(bundle.fonts().get("Regular.ttf"));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(face))).build();
        var document = bundle.compile().render(Map.of("title", title, "approved", approved,
                "items", List.of(Map.of("name", "Alpha", "count", 2), Map.of("name", "Beta", "count", 3))));
        var result = engine.export(document).backend(new PdfBackend()).to(OUTPUT.resolve(name + ".pdf"));
        if (result.display().pages().size() != 1 || !result.diagnostics().isEmpty()) { throw new IOException("unexpected template pagination/diagnostics"); }
        try (var output = Files.newOutputStream(OUTPUT.resolve(name + ".docx"))) { new DocxAdapter().write(document, output); }
    }
}
