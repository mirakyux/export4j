// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionException;
import com.mirakyux.export4j.model.ExecutionOptions;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNull;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationText;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfPassiveStructuresTest {
    @Test void defaultViewUsesDeclaredStatesAndMembershipExpressionsWithoutChangingTheGraph() throws Exception {
        try (var document = document()) {
            var on = group("Visible"); var off = group("Hidden");
            var config = new COSDictionary(); config.setName(key("BaseState"), "OFF"); config.setItem(key("ON"), array(on));
            layers(document, array(on, off), config);
            var snapshot = PdfPassiveStructures.inspect(document, PdfLimits.DEFAULT);
            assertTrue(snapshot.hasLayers()); assertFalse(snapshot.hasTags());
            assertTrue(snapshot.visible(on)); assertFalse(snapshot.visible(off));
            for (String policy : List.of("AnyOn", "AllOn", "AnyOff", "AllOff")) {
                var membership = new COSDictionary(); membership.setName(COSName.TYPE, "OCMD"); membership.setItem(key("OCGs"), array(on, off)); membership.setName(COSName.P, policy);
                assertEquals(policy.equals("AnyOn") || policy.equals("AnyOff"), snapshot.visible(membership));
            }
            var expression = new COSDictionary(); expression.setName(COSName.TYPE, "OCMD");
            expression.setItem(key("VE"), array(key("And"), on, array(key("Not"), off)));
            assertTrue(snapshot.visible(expression));
            assertEquals("OFF", config.getNameAsString(key("BaseState"))); assertEquals(1, config.getCOSArray(key("ON")).size());
        }
    }

    @Test void explicitViewUsageOverridesBaseStateButPrintUsageDoesNot() throws Exception {
        try (var document = document()) {
            var off = group("View off"); var usage = new COSDictionary(); var view = new COSDictionary(); view.setName(key("ViewState"), "OFF"); usage.setItem(key("View"), view); off.setItem(key("Usage"), usage);
            var on = group("Print off only"); var printing = new COSDictionary(); printing.setName(key("PrintState"), "OFF"); var printUsage = new COSDictionary(); printUsage.setItem(key("Print"), printing); on.setItem(key("Usage"), printUsage);
            layers(document, array(off, on), new COSDictionary());
            var snapshot = PdfPassiveStructures.inspect(document, PdfLimits.DEFAULT);
            assertFalse(snapshot.visible(off)); assertTrue(snapshot.visible(on));
        }
    }

    @Test void undeclaredGroupsAndConflictingConfigurationCannotMakeContentVisible() throws Exception {
        try (var document = document()) {
            var group = group("Declared"); var config = new COSDictionary(); layers(document, array(group), config);
            config.setItem(key("ON"), array(group)); config.setItem(key("OFF"), array(group));
            assertInvalid(() -> PdfPassiveStructures.inspect(document, PdfLimits.DEFAULT));
            config.removeItem(key("OFF")); config.setItem(key("ON"), array(group("Undeclared")));
            assertInvalid(() -> PdfPassiveStructures.inspect(document, PdfLimits.DEFAULT));
        }
    }

    @Test void visibilityExpressionsRejectUnknownReferencesAndAreDepthBounded() throws Exception {
        try (var document = document()) {
            var group = group("Declared"); layers(document, array(group), new COSDictionary());
            var snapshot = PdfPassiveStructures.inspect(document, PdfLimits.DEFAULT);
            var membership = new COSDictionary(); membership.setName(COSName.TYPE, "OCMD"); membership.setItem(key("VE"), array(key("Not"), group("Unknown")));
            assertInvalid(() -> snapshot.visible(membership));
            var cycle = new COSArray(); cycle.add(key("Not")); cycle.add(cycle); membership.setItem(key("VE"), cycle);
            var failure = assertThrows(PdfOperationException.class, () -> snapshot.visible(membership));
            assertEquals(PdfOperationException.Code.DEPTH_LIMIT, failure.code());
        }
    }

    @Test void validatedPageMcidParentTreeAndRoleMapHaveOnlyLocalBackReferenceExceptions() throws Exception {
        try (var document = document()) {
            var tree = tagged(document, "/P << /MCID 0 >> BDC EMC");
            var roleMap = new COSDictionary(); roleMap.setItem(key("ReportParagraph"), COSName.P); tree.root().setItem(key("RoleMap"), roleMap); tree.element().setName(COSName.S, "ReportParagraph");
            var snapshot = PdfPassiveStructures.inspect(document, PdfLimits.DEFAULT);
            assertTrue(snapshot.hasTags()); assertFalse(snapshot.hasLayers());
            assertTrue(snapshot.structuralBackReference(tree.element(), COSName.P));
            assertTrue(snapshot.structuralBackReference(tree.element(), key("Pg")));
            assertFalse(snapshot.structuralBackReference(new COSDictionary(), COSName.P));
            assertFalse(snapshot.structuralBackReference(tree.element(), COSName.K));
            assertEquals(tree.root(), tree.element().getDictionaryObject(COSName.P));
        }
    }

    @Test void missingDuplicateAndWrongParentMcidAssociationsAreInvalid() throws Exception {
        for (String commands : List.of("/P << /MCID 0 >> BDC EMC /P << /MCID 0 >> BDC EMC", "/P << /MCID 1 >> BDC EMC", "")) {
            try (var document = document()) { tagged(document, commands); assertInvalid(() -> PdfPassiveStructures.inspect(document, PdfLimits.DEFAULT)); }
        }
        try (var document = document()) {
            var tree = tagged(document, "/P << /MCID 0 >> BDC EMC"); tree.parentArray().set(0, COSNull.NULL);
            assertInvalid(() -> PdfPassiveStructures.inspect(document, PdfLimits.DEFAULT));
            tree.parentArray().set(0, tree.element()); tree.element().setItem(COSName.P, new COSDictionary());
            assertInvalid(() -> PdfPassiveStructures.inspect(document, PdfLimits.DEFAULT));
        }
    }

    @Test void structureChildCyclesParentTreeBoundsAndCrossPageOwnersAreRejected() throws Exception {
        try (var document = document()) {
            var tree = tagged(document, "/P << /MCID 0 >> BDC EMC"); tree.element().setItem(COSName.K, tree.element());
            assertInvalid(() -> PdfPassiveStructures.inspect(document, PdfLimits.DEFAULT));
            tree.element().setInt(COSName.K, 0); tree.parentTree().setItem(key("Limits"), array(COSInteger.ONE, COSInteger.ONE));
            assertInvalid(() -> PdfPassiveStructures.inspect(document, PdfLimits.DEFAULT));
            tree.parentTree().removeItem(key("Limits")); var foreign = new PDPage(); tree.element().setItem(key("Pg"), foreign);
            assertInvalid(() -> PdfPassiveStructures.inspect(document, PdfLimits.DEFAULT));
        }
    }

    @Test void markedFormStreamUsesItsOwnStructParentsAndExplicitPageAssociation() throws Exception {
        try (var document = document()) {
            var tree = tagged(document, ""); tree.page().getCOSObject().removeItem(key("StructParents"));
            var form = new PDFormXObject(document); form.setBBox(new PDRectangle(100, 50)); form.setResources(new PDResources()); form.getCOSObject().setInt(key("StructParents"), 0);
            try (var output = form.getCOSObject().createOutputStream()) { output.write("/P << /MCID 0 >> BDC EMC".getBytes(StandardCharsets.US_ASCII)); }
            tree.page().getResources().put(key("Fm"), form);
            var reference = new COSDictionary(); reference.setName(COSName.TYPE, "MCR"); reference.setItem(key("Pg"), tree.page()); reference.setItem(key("Stm"), form); reference.setInt(key("MCID"), 0); tree.element().setItem(COSName.K, reference);
            var snapshot = PdfPassiveStructures.inspect(document, PdfLimits.DEFAULT);
            assertTrue(snapshot.hasTags()); assertTrue(snapshot.structuralBackReference(reference, key("Stm")));
            reference.setItem(key("Pg"), new PDPage()); assertInvalid(() -> PdfPassiveStructures.inspect(document, PdfLimits.DEFAULT));
        }
    }

    @Test void objectReferencesRequireMatchingAnnotationOwnerAndParentTreeEntry() throws Exception {
        try (var document = document()) {
            var tree = tagged(document, ""); tree.page().getCOSObject().removeItem(key("StructParents"));
            var annotation = new PDAnnotationText(); annotation.setRectangle(new PDRectangle(5, 5, 10, 10)); annotation.getCOSObject().setInt(key("StructParent"), 0); tree.page().setAnnotations(List.of(annotation));
            var reference = new COSDictionary(); reference.setName(COSName.TYPE, "OBJR"); reference.setItem(key("Pg"), tree.page()); reference.setItem(key("Obj"), annotation); tree.element().setItem(COSName.K, reference);
            tree.parentTree().setItem(key("Nums"), array(COSInteger.ZERO, tree.element()));
            assertTrue(PdfPassiveStructures.inspect(document, PdfLimits.DEFAULT).hasTags());
            annotation.getCOSObject().setInt(key("StructParent"), 1); assertInvalid(() -> PdfPassiveStructures.inspect(document, PdfLimits.DEFAULT));
        }
    }

    @Test void activeStructureAttributesAndRoleCyclesRemainRejected() throws Exception {
        try (var document = document()) {
            var tree = tagged(document, "/P << /MCID 0 >> BDC EMC"); var action = new COSDictionary(); action.setName(COSName.S, "JavaScript"); tree.element().setItem(COSName.A, action);
            var failure = assertThrows(PdfOperationException.class, () -> PdfPassiveStructures.inspect(document, PdfLimits.DEFAULT));
            assertEquals(PdfOperationException.Code.UNSUPPORTED_FEATURE, failure.code());
            tree.element().removeItem(COSName.A); var roles = new COSDictionary(); roles.setItem(key("One"), key("Two")); roles.setItem(key("Two"), key("One")); tree.root().setItem(key("RoleMap"), roles);
            assertInvalid(() -> PdfPassiveStructures.inspect(document, PdfLimits.DEFAULT));
        }
    }

    @Test void graphOperatorAndCancellationBudgetsApplyBeforeSuccessfulInspection() throws Exception {
        try (var document = document()) {
            tagged(document, "/P << /MCID 0 >> BDC EMC");
            var objectBudget = PdfLimits.DEFAULT.toBuilder().maxObjects(2).build();
            assertEquals(PdfOperationException.Code.OBJECT_LIMIT, assertThrows(PdfOperationException.class, () -> PdfPassiveStructures.inspect(document, objectBudget)).code());
            var operatorBudget = PdfLimits.DEFAULT.toBuilder().maxContentOperators(1).build();
            assertEquals(PdfOperationException.Code.OPERATOR_LIMIT, assertThrows(PdfOperationException.class, () -> PdfPassiveStructures.inspect(document, operatorBudget)).code());
            assertThrows(ExecutionException.class, () -> ExecutionContext.execute(ExecutionOptions.builder().cancelled(() -> true).build(), () -> PdfPassiveStructures.inspect(document, PdfLimits.DEFAULT)));
        }
    }

    private static PDDocument document() { var document = new PDDocument(); var page = new PDPage(new PDRectangle(200, 100)); page.setResources(new PDResources()); document.addPage(page); return document; }
    private static COSName key(String value) { return COSName.getPDFName(value); }
    private static COSArray array(COSBase... values) { var result = new COSArray(); for (var value : values) { result.add(value); } return result; }
    private static COSDictionary group(String name) { var group = new COSDictionary(); group.setName(COSName.TYPE, "OCG"); group.setString(COSName.NAME, name); return group; }
    private static void layers(PDDocument document, COSArray groups, COSDictionary config) { var properties = new COSDictionary(); properties.setItem(key("OCGs"), groups); properties.setItem(COSName.D, config); document.getDocumentCatalog().getCOSObject().setItem(key("OCProperties"), properties); }
    private record Tagged(PDPage page, COSDictionary root, COSDictionary element, COSDictionary parentTree, COSArray parentArray) { }
    private static Tagged tagged(PDDocument document, String commands) throws Exception {
        var page = document.getPage(0); page.getCOSObject().setInt(key("StructParents"), 0);
        var stream = new PDStream(document); try (var output = stream.createOutputStream()) { output.write(commands.getBytes(StandardCharsets.US_ASCII)); } page.setContents(stream);
        var root = new COSDictionary(); root.setName(COSName.TYPE, "StructTreeRoot");
        var element = new COSDictionary(); element.setName(COSName.TYPE, "StructElem"); element.setName(COSName.S, "P"); element.setItem(COSName.P, root); element.setItem(key("Pg"), page); element.setInt(COSName.K, 0); root.setItem(COSName.K, element);
        var parents = array(element); var parentTree = new COSDictionary(); parentTree.setItem(key("Nums"), array(COSInteger.ZERO, parents)); root.setItem(key("ParentTree"), parentTree); root.setInt(key("ParentTreeNextKey"), 1);
        document.getDocumentCatalog().getCOSObject().setItem(key("StructTreeRoot"), root);
        var marked = new COSDictionary(); marked.setBoolean(key("Marked"), true); document.getDocumentCatalog().getCOSObject().setItem(key("MarkInfo"), marked);
        return new Tagged(page, root, element, parentTree, parents);
    }
    @FunctionalInterface private interface Operation { void run() throws Exception; }
    private static void assertInvalid(Operation operation) { assertEquals(PdfOperationException.Code.INVALID_PDF, assertThrows(PdfOperationException.class, operation::run).code()); }
}
