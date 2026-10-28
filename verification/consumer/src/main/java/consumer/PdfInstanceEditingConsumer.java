// SPDX-License-Identifier: Apache-2.0
package consumer;

import com.mirakyux.export4j.adapter.pdf.PdfAdapter;
import com.mirakyux.export4j.adapter.pdf.PdfFont;
import com.mirakyux.export4j.model.ImageResource;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Public API verification against independently authored content streams and shared resources. */
public final class PdfInstanceEditingConsumer {
    private PdfInstanceEditingConsumer() { }
    public static void main(String[] args) throws Exception {
        var fixtures = Path.of("../../build/verification/pdf-instance-edit-fixtures");
        var output = Path.of(System.getProperty("consumer.outputDirectory")).resolve("pdf-instance-edit"); Files.createDirectories(output);
        var adapter = new PdfAdapter(); var font = PdfFont.of(Files.readAllBytes(Path.of("../../assets/fonts/LiberationSans-Regular.ttf")));
        for (String name : List.of("font", "color", "spacing", "td", "tm", "other-line")) {
            var source = fixtures.resolve(name + ".pdf"); Files.copy(source, output.resolve(name + "-source.pdf"), StandardCopyOption.REPLACE_EXISTING);
            var input = adapter.input(source, ""); var protectedFile = output.resolve(name + "-protected.pdf"); Files.writeString(protectedFile, "OLD");
            if (!name.equals("other-line")) {
                try { adapter.editText(input).replace(0, "SECRET", "HI", font).prepare().to(protectedFile); throw new IllegalStateException("strict text edit unexpectedly succeeded"); }
                catch (ProcessingException expected) { require(Files.readString(protectedFile).equals("OLD"), "strict edit published"); }
            }
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                var candidate = adapter.editText(input).replace(0, "SECRET", "HI", font).policy(policy(action)).prepare();
                var result = candidate.to(output.resolve(name + "-" + action.name().toLowerCase(Locale.ROOT) + ".pdf"));
                require(result.changes() == (name.equals("other-line") ? 0 : 1), "actual cross-state match count");
                require(result.report().issues().equals(candidate.preview().issues()), "candidate report changed");
                require(action != ProcessingPolicy.Action.IGNORE || result.report().warnings().isEmpty(), "ignore emitted warnings");
            }
        }
        var raster = new BufferedImage(3, 3, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 3; y++) { for (int x = 0; x < 3; x++) { raster.setRGB(x, y, 0x800000ff); } }
        var pixels = new ByteArrayOutputStream(); ImageIO.write(raster, "png", pixels); raster.flush(); var replacement = ImageResource.load(pixels.toByteArray());
        for (String name : List.of("images", "images-alpha")) {
            var source = fixtures.resolve(name + ".pdf"); Files.copy(source, output.resolve(name + "-source.pdf"), StandardCopyOption.REPLACE_EXISTING);
            var input = adapter.input(source, ""); require(adapter.editImages(input).instances().size() == 8, "instance traversal count");
            for (var action : List.of(ProcessingPolicy.Action.WARN, ProcessingPolicy.Action.IGNORE)) {
                var candidate = adapter.editImages(input).replace(0, 2, replacement).policy(policy(action)).prepare();
                var result = candidate.to(output.resolve(name + "-" + action.name().toLowerCase(Locale.ROOT) + ".pdf"));
                require(result.changes() == 1, "image invocation count");
                require(candidate.preview().losses().size() == (name.endsWith("alpha") ? 1 : 0), "mask discard report");
            }
        }
        Files.writeString(output.resolve("summary.json"), "{\"sources\":8,\"outputs\":16,\"pages\":32}\n");
    }
    private static ProcessingPolicy policy(ProcessingPolicy.Action action) {
        return ProcessingPolicy.builder().feature("TEXT_FONT", action, ProcessingPolicy.Treatment.DEGRADE)
                .feature("TEXT_STYLE", action, ProcessingPolicy.Treatment.DEGRADE).feature("TEXT_SPACING", action, ProcessingPolicy.Treatment.DEGRADE)
                .feature("IMAGE_MASK", action, ProcessingPolicy.Treatment.DISCARD).build();
    }
    private static void require(boolean value, String message) { if (!value) { throw new IllegalStateException(message); } }
}
