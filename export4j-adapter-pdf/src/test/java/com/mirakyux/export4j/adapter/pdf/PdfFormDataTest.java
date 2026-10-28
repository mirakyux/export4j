// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingException;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfFormDataTest {
    @Test void bothStandardFormatsPreserveExactUnicodeAndArrays()throws Exception{
        var fields=List.of(new PdfFormData.FieldValue("form.name",List.of("雪 & <text>\nline"),""),new PdfFormData.FieldValue("choice",List.of("A","C"),""));
        for(var format:PdfFormData.Format.values()){byte[] bytes=PdfFormData.encode(fields,format,PdfLimits.DEFAULT);var parsed=PdfFormData.parse(bytes,format);assertEquals(fields,parsed.fields());assertTrue(parsed.report().issues().isEmpty());}
    }
    @Test void exportImportActuallyChangesExistingFieldsAndRequiresAppearanceLoss()throws Exception{
        var adapter=new PdfAdapter();var input=adapter.input(PdfExtendedFormsTest.source(),"");assertThrows(ProcessingException.class,()->adapter.formData(input).prepare());
        var policy=ProcessingPolicy.builder().feature("FORM_DATA_APPEARANCE",ProcessingPolicy.Action.WARN,ProcessingPolicy.Treatment.DISCARD).build();
        for(var format:PdfFormData.Format.values()){var exported=adapter.formData(input).format(format).policy(policy).prepare();var parsed=PdfFormData.parse(exported.bytes(),format);assertEquals(4,parsed.fields().size());var replacement=PdfFormData.of(List.of(new PdfFormData.FieldValue("multi","EXCHANGED\nSECOND"),new PdfFormData.FieldValue("choice",List.of("A","C"),"")));byte[] bytes=PdfFormData.encode(replacement.fields(),format,PdfLimits.DEFAULT);var candidate=adapter.editObjects(input).importFormData(PdfFormData.parse(bytes,format),PdfExtendedFormsTest.font(),9).prepare();var snapshot=adapter.readObjects(adapter.input(PdfExtendedFormsTest.bytes(candidate),""));assertEquals("EXCHANGED\nSECOND",snapshot.fields().getFirst().value());assertEquals(List.of("A","C"),snapshot.fields().get(3).values());}
    }
    @Test void passiveUnknownAttributesAreLossesButActionsAndEntitiesAreNeverExecuted()throws Exception{
        String unknown="<xfdf xmlns='http://ns.adobe.com/xfdf/'><fields><field name='x' custom='passive'><value>ONE</value></field></fields></xfdf>";
        assertThrows(ProcessingException.class,()->PdfFormData.parse(unknown.getBytes(StandardCharsets.UTF_8),PdfFormData.Format.XFDF));
        for(var action:List.of(ProcessingPolicy.Action.WARN,ProcessingPolicy.Action.IGNORE)){var policy=ProcessingPolicy.builder().feature("FORM_DATA_PROPERTIES",action,ProcessingPolicy.Treatment.DISCARD).build();var result=PdfFormData.parse(unknown.getBytes(StandardCharsets.UTF_8),PdfFormData.Format.XFDF,PdfLimits.DEFAULT,policy,ExecutionOptions.DEFAULT);assertEquals("FORM_DATA_PROPERTIES",result.report().issues().getFirst().feature());}
        for(String input:List.of("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///unavailable'>]><xfdf xmlns='http://ns.adobe.com/xfdf/'><fields/></xfdf>","<xfdf xmlns='http://ns.adobe.com/xfdf/'><fields/><script>bad</script></xfdf>","<xfdf xmlns='http://ns.adobe.com/xfdf/'><f href='https://example.invalid/source.pdf'/><fields/></xfdf>","<xfdf xmlns='http://ns.adobe.com/xfdf/'><fields><field name='x'><value>VALUE</value><value-richtext><body xmlns='http://www.w3.org/1999/xhtml'><script>bad</script></body></value-richtext></field></fields></xfdf>")){assertThrows(java.io.IOException.class,()->PdfFormData.parse(input.getBytes(StandardCharsets.UTF_8),PdfFormData.Format.XFDF));}
        String fdf="%FDF-1.2\n1 0 obj<</FDF<</Fields[<</T(x)/V(y)/A<</S/JavaScript/JS(evil)>>>>]>>>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF";
        assertThrows(java.io.IOException.class,()->PdfFormData.parse(fdf.getBytes(StandardCharsets.US_ASCII),PdfFormData.Format.FDF));
    }
}
