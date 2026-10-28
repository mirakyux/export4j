// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.adapter.pdf;

import com.mirakyux.export4j.model.ExecutionContext;
import com.mirakyux.export4j.model.ExecutionOptions;
import com.mirakyux.export4j.model.processing.ProcessingReport;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Exact bounded FDF/XFDF candidate. Borrowed streams remain open; path publication is atomic. */
public final class PreparedPdfFormData {
    public record Result(int fields,long outputBytes,ProcessingReport report) { }
    private final byte[] bytes;private final PdfFormData data;private final Result result;private final ExecutionOptions execution;
    PreparedPdfFormData(byte[] bytes,PdfFormData data,ProcessingReport report,ExecutionOptions execution){this.bytes=bytes.clone();this.data=data;this.result=new Result(data.fields().size(),bytes.length,report);this.execution=execution;}
    public PdfFormData data(){return data;}
    public byte[] bytes(){return bytes.clone();}
    public ProcessingReport preview(){return new ProcessingReport(ProcessingReport.Phase.PREVIEW,result.report().issues());}
    public Result to(OutputStream output)throws IOException{Objects.requireNonNull(output);return ExecutionContext.execute(execution,()->{ExecutionContext.output(output).write(bytes);return result;});}
    public Result to(Path output)throws IOException{Path target=Objects.requireNonNull(output).toAbsolutePath();Path temporary=Files.createTempFile(target.getParent(),".export4j-form-data-",".tmp");try{return ExecutionContext.execute(execution,()->{try(var stream=Files.newOutputStream(temporary)){to(stream);}ExecutionContext.checkpoint();Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);return result;});}catch(IOException|RuntimeException|Error failure){try{Files.deleteIfExists(temporary);}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;}}
}
