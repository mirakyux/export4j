// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.designer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mirakyux.export4j.graphics.DrawCommand;
import com.mirakyux.export4j.graphics.DisplayPage;
import com.mirakyux.export4j.adapter.template.XmlTemplateBundle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class DesignerCommonSamplesTest {
    private record Fixture(int index, String name, String titleId, String bodyId, String titleObject, int pages) { }

    private static final List<Fixture> FIXTURES = List.of(
            new Fixture(5, "服务合同", "contract-title", "contract-parties", "", 7),
            new Fixture(6, "个人简历", "resume-name", "resume-profile", "", 2),
            new Fixture(7, "用户手册", "manual-title", "manual-installation", "", 8),
            new Fixture(8, "报价单", "q-title", "q-items", "quote", 4),
            new Fixture(9, "会议纪要", "m-title", "m-agenda", "meeting", 3),
            new Fixture(10, "项目周报", "w-title", "w-metrics", "report", 4));

    @Test void addingCommonSamplesPreservesExistingCatalogIndexes() throws Exception {
        var catalog = DesignerSamples.catalog();
        assertEquals(List.of("巡检报告", "密集静态表单", "分页长表", "目录与交叉引用", "药品不良反应报告表"),
                catalog.subList(0, 5).stream().map(value -> value.get("name")).toList());
        assertEquals(11, catalog.size());
        for (var fixture : FIXTURES) {
            var entry = catalog.get(fixture.index());
            assertEquals(fixture.index(), entry.get("index"));
            assertEquals(fixture.name(), entry.get("name"));
            assertFalse(entry.get("description").toString().isBlank());
        }
        assertTrue(DesignerSamples.create(4).bundle().source().contains("id=\"adr-patient\""));
    }

    @TestFactory Stream<DynamicTest> commonSamplesPreviewWithRealDataAndReactToBindingEdits() {
        return FIXTURES.stream().map(fixture -> DynamicTest.dynamicTest(fixture.name(), () -> {
            var project = DesignerSamples.create(fixture.index());
            if (fixture.index() == 5 || fixture.index() == 6) {
                var bundle = project.bundle();
                var reopened = XmlTemplateBundle.read(bundle.encode());
                String reference = fixture.index() == 5 ? "contract_logo" : "resume_portrait";
                assertEquals(Set.of(reference), reopened.images().keySet(), "image resource name changed while saving template");
                assertArrayEquals(bundle.images().get(reference).bytes(), reopened.images().get(reference).bytes());
                assertEquals(bundle.licenses(), reopened.licenses(), "image source/license notices changed while saving template");
                assertFalse(reopened.licenses().getOrDefault(reference + "-license.txt", "").isBlank(),
                        "image source/license notice must be present in the saved template");
                assertEquals(bundle.source(), reopened.source(), "saving a template must retain the editable image binding");
                project = new DesignerProject(reopened, project.data());
            }
            var source = project.bundle().source();
            var data = BindingJson.parse(project.data());
            var preview = DesignerService.preview(project, DesignerService.execution(() -> false));
            var pages = preview.layout().display().pages();
            verifyPictures(fixture, pages);
            assertTrue(pages.size() >= 2, "expanded sample must demonstrate real pagination");
            assertEquals(fixture.pages(), pages.size(), "designer pagination differs from the standalone expanded example");
            assertTrue(project.bundle().licenses().containsKey("LXGWWenKaiTC-OFL.txt"));
            assertTrue(preview.hits().stream().anyMatch(hit -> hit.id().equals(fixture.titleId()) && hit.page() == 0));
            assertTrue(preview.hits().stream().anyMatch(hit -> hit.id().equals(fixture.bodyId())));
            for (int index = 0; index < pages.size(); index++) {
                var page = pages.get(index);
                assertEquals(595.3, page.size().width(), .01);
                assertEquals(841.9, page.size().height(), .01);
                assertTrue(text(page).contains((index + 1) + "/" + pages.size()), "final footer page/count not resolved");
                assertFalse(text(page).contains("${"), "unresolved template binding in final display");
            }

            String marker = "样例编辑验证";
            var editedData = title(data, fixture.titleObject(), marker);
            var changed = project.source(source, BindingJson.write(editedData));
            var edited = DesignerService.preview(changed, DesignerService.execution(() -> false));
            assertTrue(edited.layout().display().pages().stream().map(DesignerCommonSamplesTest::text)
                    .anyMatch(value -> value.contains(marker)), "editing test data did not change visible document text");
            var editedPages = edited.layout().display().pages();
            for (int index = 0; index < editedPages.size(); index++) {
                assertTrue(text(editedPages.get(index)).contains((index + 1) + "/" + editedPages.size()),
                        "edited document footer page/count not resolved after reflow");
            }
            assertFalse(pages.stream().map(DesignerCommonSamplesTest::text).anyMatch(value -> value.contains(marker)));
            assertEquals(source, changed.bundle().source(), "data editing must retain dynamic XML source");
            assertEquals(data, BindingJson.parse(project.data()), "editing must retain the original project snapshot");
        }));
    }

    private static void verifyPictures(Fixture fixture, List<DisplayPage> pages) {
        if (fixture.index() != 5 && fixture.index() != 6) { return; }
        boolean contract = fixture.index() == 5;
        for (int page = 0; page < pages.size(); page++) {
            var images = pictures(pages.get(page).commands());
            int count = contract || page == 0 ? 1 : 0;
            assertEquals(count, images.size(), "header logo/first-page portrait was lost or duplicated");
            if (count == 1) {
                var picture = images.getFirst();
                assertEquals(contract ? 256 : 384, picture.image().width());
                assertEquals(contract ? 256 : 480, picture.image().height());
                assertEquals(contract ? 25 : 64, picture.bounds().width(), .001);
                assertEquals(contract ? 25 : 80, picture.bounds().height(), .001);
            }
        }
    }

    private static List<DrawCommand.Image> pictures(List<DrawCommand> commands) {
        var result = new ArrayList<DrawCommand.Image>();
        for (var command : commands) {
            if (command instanceof DrawCommand.Image image) { result.add(image); }
            else if (command instanceof DrawCommand.Group group) { result.addAll(pictures(group.commands())); }
        }
        return result;
    }

    private static Map<String, Object> title(Map<String, Object> data, String object, String value) {
        var result = new LinkedHashMap<String, Object>(data);
        if (object.isEmpty()) {
            assertTrue(result.get("title") instanceof String);
            result.put("title", value);
        } else {
            if (!(data.get(object) instanceof Map<?, ?> original)) {
                throw new AssertionError("sample has no title binding object: " + object);
            }
            assertTrue(original.get("title") instanceof String);
            var nested = new LinkedHashMap<String, Object>();
            original.forEach((key, item) -> nested.put(key.toString(), item));
            nested.put("title", value);
            result.put(object, nested);
        }
        return result;
    }

    private static String text(DisplayPage page) {
        return text(page.commands()).replaceAll("\\s+", "");
    }

    private static String text(List<DrawCommand> commands) {
        var result = new StringBuilder();
        for (var command : commands) {
            switch (command) {
                case DrawCommand.GlyphText glyph -> result.append(glyph.run().text());
                case DrawCommand.Text simple -> result.append(simple.text());
                case DrawCommand.OutlineText outline -> result.append(outline.logicalText());
                case DrawCommand.Group group -> result.append(text(group.commands()));
                default -> { }
            }
        }
        return result.toString();
    }
}
