// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingIssue;
import com.mirakyux.export4j.model.processing.ProcessingPolicy;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Task-confined export of exact field values; appearances and document objects remain in the PDF. */
public final class PdfFormDataRequest {
    private final PdfAdapter adapter;private final PdfInput input;private final PdfLimits limits;private PdfFormData.Format format=PdfFormData.Format.XFDF;private ProcessingPolicy policy=ProcessingPolicy.STRICT;private ExecutionOptions execution=ExecutionOptions.DEFAULT;
    PdfFormDataRequest(PdfAdapter adapter,PdfInput input,PdfLimits limits){this.adapter=adapter;this.input=input;this.limits=limits;}
    public PdfFormDataRequest format(PdfFormData.Format value){format=Objects.requireNonNull(value);return this;}
    public PdfFormDataRequest policy(ProcessingPolicy value){policy=Objects.requireNonNull(value);return this;}
    public PdfFormDataRequest execution(ExecutionOptions value){execution=Objects.requireNonNull(value);return this;}
    public PreparedPdfFormData prepare()throws IOException{var target=format;var strategy=policy;var control=execution;return ExecutionContext.execute(control,()->{try(var document=adapter.open(input,adapter.preflight(true))){if(!document.getCurrentAccessPermission().canExtractContent()){throw new PdfOperationException(PdfOperationException.Code.PERMISSION_DENIED,0,-1);}var snapshot=PdfObjects.inspect(document,limits);var fields=new ArrayList<PdfFormData.FieldValue>();var issues=new ArrayList<ProcessingIssue>();
        for(var field:snapshot.fields()){ExecutionContext.checkpoint();fields.add(new PdfFormData.FieldValue(field.name(),field.values(),field.richValue()));}
        if(!fields.isEmpty()){PdfFormData.loss(strategy,issues,target.name(),"FORM_DATA_APPEARANCE","EXPORT_FORM_DATA",ProcessingPolicy.Treatment.DISCARD,"FIELD_GEOMETRY_FONTS_AND_APPEARANCES_NOT_IN_DATA");}
        var data=new PdfFormData(fields,List.of());byte[] bytes=PdfFormData.encode(fields,target,limits);return new PreparedPdfFormData(bytes,data,new ProcessingReport(ProcessingReport.Phase.ACTUAL,issues),control);}});}
}
