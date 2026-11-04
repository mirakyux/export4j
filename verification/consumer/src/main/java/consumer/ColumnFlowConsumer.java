// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.template.XmlTemplate;
import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import com.mirakyux.export4j.adapter.template.XmlTemplateWriter;
import com.mirakyux.export4j.backend.pdf.PdfBackend;
import com.mirakyux.export4j.backend.svg.SvgBackend;
import com.mirakyux.export4j.components.DocumentEngine;
import com.mirakyux.export4j.font.FontGroup;
import com.mirakyux.export4j.font.TrueTypeFontLoader;
import com.mirakyux.export4j.model.ColumnFlow;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Published-artifact acceptance of continuous semantic columns interrupted by full-width content. */
public final class ColumnFlowConsumer {
    private ColumnFlowConsumer() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) { throw new IllegalArgumentException("No arguments expected"); }
        var output = Path.of(System.getProperty("consumer.outputDirectory", "build/output")).resolve("column-flow");
        Files.createDirectories(output);
        var fontBytes = Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf"));
        var fontLicense = Files.readString(Path.of("../../assets/fonts/LiberationSans-OFL.txt"));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(fontBytes)))).build();
        var image = new BufferedImage(840, 80, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, x < 280 ? 0x264b70 : x < 560 ? 0x90c3b3 : 0xe9ba7d);
            }
        }
        var encoded = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", encoded)) { throw new IllegalStateException("PNG writer unavailable"); }
        image.flush();
        var compiler = XmlTemplate.builder().image("wide", ImageResource.load(encoded.toByteArray())).build();
        for (var name : List.of("two", "three", "weighted", "multipage")) {
            int count = name.equals("three") ? 3 : 2;
            int paragraphs = name.equals("multipage") ? 20 : 4;
            String weights = name.equals("weighted") ? "1 2" : "";
            var tags = new ArrayList<String>();
            var before = paragraphs("BEFORE", paragraphs, tags);
            tags.add("WIDE-TABLE"); tags.add("TABLE-VALUE");
            var after = paragraphs("AFTER", paragraphs, tags);
            tags.add("FULL-WIDTH-END");
            String source = """
                    <document version="1" title="Column flow acceptance">
                      <page width="480pt" height="600pt" margin="30pt"/>
                      <p id="title" size="17" line-height="23" space-after="10">Continuous columns: %s</p>
                      <columns id="before" count="%d" gap="18" weights="%s" balanced="true">
                        <each items="before" as="item"><p id="first" size="10" line-height="14" space-after="6">${item}</p></each>
                      </columns>
                      <table id="wide-table" space-before="8" space-after="8">
                        <columns><column weight="1"/><column weight="2"/></columns>
                        <row><cell size="10" line-height="14">WIDE-TABLE</cell><cell size="10" line-height="14">TABLE-VALUE</cell></row>
                      </table>
                      <p id="wide-image" line-height="44" space-after="8"><image ref="wide" width="420" height="40" alt="Full width colored figure"/></p>
                      <columns id="after" count="%d" gap="18" weights="%s" balanced="true">
                        <if test="show"><then><each items="after" as="item"><p id="last" size="10" line-height="14" space-after="6">${item}</p></each></then></if>
                      </columns>
                      <p id="tail" size="10" line-height="14" space-before="8">FULL-WIDTH-END</p>
                    </document>
                    """.formatted(name, count, weights, count, weights);
            Files.writeString(output.resolve(name + ".xml"), source);
            var document = compiler.compile(source).render(Map.of("before", before, "after", after, "show", true));
            var prepared = XmlTemplateWriter.from(document).font("Regular.ttf", fontBytes)
                    .license("Font.txt", fontLicense)
                    .policy(ProcessingPolicy.builder().feature("IDENTIFIER", ProcessingPolicy.Action.WARN,
                            ProcessingPolicy.Treatment.DEGRADE).build()).prepare();
            if (prepared.preview().issues().size() != paragraphs * 2
                    || prepared.preview().issues().stream().anyMatch(issue -> !issue.feature().equals("IDENTIFIER"))) {
                throw new IllegalStateException("Generated loop IDs must be explicitly renamed without other loss");
            }
            var bundlePath = output.resolve(name + ".zip");
            prepared.to(bundlePath);
            var saved = XmlTemplateBundle.read(Files.readAllBytes(bundlePath));
            if (saved.images().size() != 1 || !Arrays.equals(encoded.toByteArray(), saved.images().values().iterator().next().bytes())) {
                throw new IllegalStateException("Original image bytes changed in the saved ZIP");
            }
            var reopened = saved.compile().render(Map.of());
            var flows = reopened.blocks().stream().filter(ColumnFlow.class::isInstance).map(ColumnFlow.class::cast).toList();
            if (flows.size() != 2 || flows.stream().anyMatch(flow -> flow.columns() != count || flow.gap() != 18
                    || !flow.balanced() || flow.blocks().size() != paragraphs
                    || !flow.columnWeights().equals(weights.isEmpty() ? List.of() : List.of(1.0, 2.0)))) {
                throw new IllegalStateException("Column configuration or content changed after ZIP reopen");
            }
            var result = engine.export(reopened).backend(new PdfBackend()).to(output.resolve(name + ".pdf"));
            if (!result.contextualDiagnostics().isEmpty()) { throw new IllegalStateException("Unexpected layout diagnostics"); }
            try (var svg = Files.newOutputStream(output.resolve(name + ".svg"))) { new SvgBackend().write(result.display(), svg); }
            Files.write(output.resolve(name + "-tags.txt"), tags);
            var edited = saved.toBuilder().source(saved.source().replace("BEFORE-01", "EDITED-01")).build();
            var editedPath = output.resolve(name + "-edited.zip");
            Files.write(editedPath, edited.encode());
            var changed = XmlTemplateBundle.read(Files.readAllBytes(editedPath)).compile().render(Map.of());
            engine.export(changed).backend(new PdfBackend()).to(output.resolve(name + "-edited.pdf"));
        }
        System.out.println("ColumnFlow: four XML/ZIP reopen cases and eight PDF exports completed");
    }

    private static List<String> paragraphs(String prefix, int count, List<String> tags) {
        var values = new ArrayList<String>();
        for (int index = 1; index <= count; index++) {
            String tag = "%s-%02d".formatted(prefix, index);
            tags.add(tag);
            values.add(tag + " Each paragraph remains a semantic source. Text flows down a column and continues in the next, preserving its reading order.");
        }
        return List.copyOf(values);
    }
}
