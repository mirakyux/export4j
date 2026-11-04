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
import com.mirakyux.export4j.model.GridLayout;
import com.mirakyux.export4j.model.GridItem;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.Paragraph;
import com.mirakyux.export4j.model.SemanticTree;
import com.mirakyux.export4j.model.Table;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Isolated published-API exercise of explicit grid tracks, atomic rows and editable source bundles. */
public final class GridLayoutConsumer {
    private GridLayoutConsumer() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) { throw new IllegalArgumentException("No arguments expected"); }
        var output = Path.of(System.getProperty("consumer.outputDirectory", "build/output")).resolve("grid-layout");
        Files.createDirectories(output);
        byte[] font = Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf"));
        String license = Files.readString(Path.of("../../assets/fonts/LiberationSans-OFL.txt"));
        var engine = DocumentEngine.builder(FontGroup.strict(List.of(TrueTypeFontLoader.load(font)))).build();
        var pixels = new BufferedImage(360, 168, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < pixels.getHeight(); y++) {
            for (int x = 0; x < pixels.getWidth(); x++) {
                pixels.setRGB(x, y, x < 120 ? 0x264b70 : x < 240 ? 0x90c3b3 : 0xe9ba7d);
            }
        }
        var imageBytes = new ByteArrayOutputStream();
        if (!ImageIO.write(pixels, "png", imageBytes)) { throw new IllegalStateException("PNG writer unavailable"); }
        pixels.flush();
        var image = ImageResource.load(imageBytes.toByteArray());
        for (var name : List.of("mixed", "thirds", "gaps", "paging")) {
            String source = source(name);
            Files.writeString(output.resolve(name + ".xml"), source);
            var document = XmlTemplate.builder().image("figure", image).build().compile(source).render(Map.of("show", true));
            var grid = document.blocks().stream().filter(GridLayout.class::isInstance).map(GridLayout.class::cast).findFirst().orElseThrow();
            var current = document;
            XmlTemplateBundle saved = null;
            for (int round = 1; round <= 3; round++) {
                var prepared = XmlTemplateWriter.from(current).font("Regular.ttf", font).license("Font.txt", license).prepare();
                if (!prepared.preview().issues().isEmpty()) { throw new IllegalStateException("Grid saving unexpectedly loses content"); }
                Path bundlePath = output.resolve(name + "-round-" + round + ".zip");
                prepared.to(bundlePath);
                saved = XmlTemplateBundle.read(Files.readAllBytes(bundlePath));
                if (saved.images().size() != 1 || !Arrays.equals(image.bytes(), saved.images().values().iterator().next().bytes())) {
                    throw new IllegalStateException("Grid image encoding changed during save");
                }
                current = saved.compile().render(Map.of());
                var reopened = current.blocks().stream().filter(GridLayout.class::isInstance).map(GridLayout.class::cast).findFirst().orElseThrow();
                if (!signature(grid).equals(signature(reopened))) { throw new IllegalStateException("Grid tracks, items or content changed after reopening"); }
            }
            if (saved == null) { throw new IllegalStateException("Missing saved bundle"); }
            Files.write(output.resolve(name + ".zip"), saved.encode());
            var result = engine.export(current).backend(new PdfBackend()).to(output.resolve(name + ".pdf"));
            if (!result.contextualDiagnostics().isEmpty()) { throw new IllegalStateException("Unexpected grid diagnostics"); }
            try (var target = Files.newOutputStream(output.resolve(name + ".svg"))) { new SvgBackend().write(result.display(), target); }
            String editedSource = saved.source().replace("GRID-A", "EDIT-A");
            if (name.equals("mixed")) {
                editedSource = editedSource.replace("id=\"left\" row=\"1\" column-start=\"1\" column-span=\"6\"",
                        "id=\"left\" row=\"1\" column-start=\"1\" column-span=\"8\"")
                        .replace("id=\"right\" row=\"1\" column-start=\"7\" column-span=\"6\"",
                                "id=\"right\" row=\"1\" column-start=\"9\" column-span=\"4\"");
            }
            var edited = saved.toBuilder().source(editedSource).build();
            Path editedPath = output.resolve(name + "-edited.zip");
            Files.write(editedPath, edited.encode());
            var changed = XmlTemplateBundle.read(Files.readAllBytes(editedPath)).compile().render(Map.of());
            var changedGrid = changed.blocks().stream().filter(GridLayout.class::isInstance).map(GridLayout.class::cast).findFirst().orElseThrow();
            if (name.equals("mixed") && (changedGrid.items().getFirst().columnSpan() != 8
                    || changedGrid.items().get(1).columnStart() != 9 || changedGrid.items().get(1).columnSpan() != 4)) {
                throw new IllegalStateException("Edited grid coordinates were not saved");
            }
            engine.export(changed).backend(new PdfBackend()).to(output.resolve(name + "-edited.pdf"));
        }
        System.out.println("GridLayout: four cases, three save rounds, eight original/edited PDFs completed");
    }

    private static List<Object> signature(GridLayout grid) {
        var values = new ArrayList<Object>();
        values.add(List.of(grid.id(), grid.tracks(), grid.columnGap(), grid.rowGap()));
        SemanticTree.walk(grid, visit -> {
            values.add(visit.block().id());
            if (visit.block() instanceof GridItem item) {
                values.add(List.of(item.row(), item.columnStart(), item.columnSpan()));
            } else if (visit.block() instanceof Paragraph paragraph) {
                values.add(List.of(paragraph.text(), paragraph.format(), paragraph.images(), paragraph.fields()));
            } else if (visit.block() instanceof Table table) {
                values.add(List.of(table.columns(), table.headerRows(), table.format()));
                for (var row : table.rows()) {
                    for (var cell : row.cells()) {
                        values.add(List.of(cell.text(), cell.format(), cell.columnSpan(), cell.rowSpan(), cell.images(), cell.fields()));
                    }
                }
            }
        });
        return List.copyOf(values);
    }

    private static String source(String name) {
        String grid = switch (name) {
            case "mixed" -> """
                    <grid id="layout" tracks="12" column-gap="12" row-gap="12">
                      <item id="left" row="1" column-start="1" column-span="6"><p id="a" size="10" line-height="14">GRID-A The left item keeps its original text, wrapping at the actual width of six tracks.</p></item>
                      <item id="right" row="1" column-start="7" column-span="6"><p id="b" size="10" line-height="14">GRID-B The right item remains alongside the left item, with a real gutter between them.</p></item>
                      <item id="feature" row="2" column-start="1" column-span="8"><p id="c" size="10" line-height="14">GRID-C This feature spans two thirds of the grid. The image beside it occupies the remaining four tracks.</p></item>
                      <item id="photo" row="2" column-start="9" column-span="4"><p id="d" size="10" line-height="14">GRID-D</p><p id="figure" line-height="60"><image ref="figure" width="120" height="56" alt="Synthetic colored grid figure"/></p></item>
                      <item id="third-one" row="3" column-start="1" column-span="4"><p id="e" size="10" line-height="14">GRID-E First short panel.</p></item>
                      <item id="third-two" row="3" column-start="5" column-span="4"><p id="f" size="10" line-height="14">GRID-F Second short panel.</p></item>
                      <item id="third-three" row="3" column-start="9" column-span="4"><p id="g" size="10" line-height="14">GRID-G Third short panel.</p></item>
                      <if test="show"><then><item id="offset" row="4" column-start="3" column-span="8">
                        <table id="grid-table"><columns><column weight="1"/><column weight="1"/></columns>
                          <row><cell size="10" line-height="14">GRID-H</cell><cell size="10" line-height="14">TABLE-END</cell></row>
                        </table>
                      </item></then></if>
                    </grid>
                    """;
            case "thirds" -> """
                    <grid id="layout" tracks="3" column-gap="18" row-gap="12">
                      <item id="left" row="1" column-start="1" column-span="2"><p id="a" size="10" line-height="14">GRID-A Two of three tracks form this feature. The full original paragraph remains editable.</p></item>
                      <item id="right" row="1" column-start="3" column-span="1"><p id="b" size="10" line-height="14">GRID-B</p><p id="figure" line-height="60"><image ref="figure" width="120" height="56" alt="Synthetic colored grid figure"/></p></item>
                    </grid>
                    """;
            case "gaps" -> """
                    <grid id="layout" tracks="12" column-gap="0" row-gap="24">
                      <item id="left" row="1" column-start="1" column-span="4"><p id="a" size="10" line-height="14">GRID-A Four tracks with no gutter.</p></item>
                      <item id="right" row="1" column-start="5" column-span="8"><p id="b" size="10" line-height="14">GRID-B Eight tracks alongside the first panel.</p></item>
                      <item id="offset" row="5" column-start="4" column-span="6"><p id="c" size="10" line-height="14">GRID-C Offset by three tracks; skipped row numbers do not add blank rows.</p><p id="figure" line-height="60"><image ref="figure" width="120" height="56" alt="Synthetic colored grid figure"/></p></item>
                    </grid>
                    """;
            case "paging" -> """
                    <grid id="layout" tracks="12" column-gap="12" row-gap="12">
                      <item id="left" row="1" column-start="1" column-span="6"><p id="a" size="10" line-height="14">GRID-A</p><p id="figure-one" line-height="88"><image ref="figure" width="180" height="84" alt="Synthetic row one"/></p></item>
                      <item id="right" row="1" column-start="7" column-span="6"><p id="b" size="10" line-height="14">GRID-B Same row, same physical page.</p></item>
                      <item id="left-two" row="2" column-start="1" column-span="6"><p id="c" size="10" line-height="14">GRID-C</p><p id="figure-two" line-height="88"><image ref="figure" width="180" height="84" alt="Synthetic row two"/></p></item>
                      <item id="right-two" row="2" column-start="7" column-span="6"><p id="d" size="10" line-height="14">GRID-D Same row, same physical page.</p></item>
                      <item id="left-three" row="3" column-start="1" column-span="6"><p id="e" size="10" line-height="14">GRID-E</p><p id="figure-three" line-height="88"><image ref="figure" width="180" height="84" alt="Synthetic row three"/></p></item>
                      <item id="right-three" row="3" column-start="7" column-span="6"><p id="f" size="10" line-height="14">GRID-F Same row, same physical page.</p></item>
                    </grid>
                    """;
            default -> throw new IllegalArgumentException("Unknown fixture");
        };
        String prefix = name.equals("paging") ? "<p id='prefix' size='10' line-height='230'>PREFIX-PAGING</p>" : """
                <p id="title" size="16" line-height="22" space-after="10">Editable track grid</p>
                <columns id="before" count="2" gap="18" balanced="true"><p id="before-a" size="10" line-height="14">BEFORE-A Text flows across two columns before the grid.</p><p id="before-b" size="10" line-height="14">BEFORE-B The grid begins below the completed column area.</p></columns>
                """;
        String suffix = name.equals("paging") ? "" : """
                <columns id="after" count="2" gap="18" balanced="true"><p id="after-a" size="10" line-height="14">AFTER-A Continuous columns resume below the final grid row.</p><p id="after-b" size="10" line-height="14">AFTER-B Both layout systems retain the source reading order.</p></columns>
                """;
        return "<document version='1' title='Grid layout acceptance'><page width='480' height='" + (name.equals("paging") ? 360 : 620)
                + "' margin='30'/>" + prefix + grid + suffix + "<p id='tail' size='10' line-height='14' space-before='8'>GRID-END</p></document>";
    }
}
