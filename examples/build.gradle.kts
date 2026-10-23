// SPDX-License-Identifier: Apache-2.0
plugins {
    application
}
description = "Runnable precision drawing, text, flow, table and interactive component examples"
dependencies {
    implementation(project(":export4j-graphics-core"))
    implementation(project(":export4j-backend-pdf"))
    implementation(project(":export4j-layout-core"))
    implementation(project(":export4j-components"))
    implementation(project(":export4j-backend-svg"))
    implementation(project(":export4j-backend-raster"))
    implementation(project(":export4j-adapter-docx"))
    implementation(project(":export4j-adapter-template"))
    implementation(project(":export4j-adapter-pdf"))
    implementation(project(":export4j-adapter-web"))
}
application {
    mainClass.set("com.mirakyux.export4j.examples.PdfExample")
}

tasks.register<JavaExec>("semanticEditing") {
    group = "application"
    description = "Edits semantic content and exports PDF, DOCX, HTML and Markdown with a reconciled loss report."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.SemanticEditingExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("pdfPageEditing") {
    group = "application"
    description = "Edits existing PDF page geometry and explicit text/image layers without reflow."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.PdfPageEditingExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("pdfObjectEditing") {
    group = "application"
    description = "Edits passive PDF fields, comments, links, outlines, attachments and metadata."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.PdfObjectEditingExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("pdfRaster") {
    group = "application"
    description = "Renders existing PDF pages and extracts unique embedded image resources."
    dependsOn("pdfPageEditing", "pdfObjectEditing")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.PdfRasterExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("pdfOptimization") {
    group = "application"
    description = "Optimizes PDF streams/resources and explicitly resamples images with measured losses."
    dependsOn("pdfPageEditing", "pdfObjectEditing")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.PdfOptimizationExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("pdfContentEditing") {
    group = "application"
    description = "Replaces real page text and redacts explicit regions into a fresh image-only PDF."
    dependsOn("pdfPageEditing")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.PdfContentEditingExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("pdfSemanticImport") {
    group = "application"
    description = "Recovers editable PDF semantic blocks and converts to DOCX, HTML, Markdown and PDF."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.PdfSemanticImportExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("markdownImport") {
    group = "application"
    description = "Imports CommonMark/GFM, edits its semantic model, and exports four formats."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.MarkdownImportExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("htmlImport") {
    group = "application"
    description = "Imports offline HTML/CSS, edits semantic content and exports with reconciled loss reports."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.HtmlImportExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("documentConversion") {
    group = "application"
    description = "Prepares real import/edit/export chains with exact encodings and complete actual loss reports."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.DocumentConversionExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("docxPackageEdit") {
    group = "application"
    description = "Edits generated DOCX text while preserving untouched package structures and parts."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.DocxPackageEditExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("docxStyleImport") {
    group = "application"
    description = "Resolves DOCX style inheritance to explicit semantic formatting with a real loss report."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.DocxStyleImportExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("webExports") {
    group = "application"
    description = "Generates semantic HTML and Markdown with explicit unsupported-content policies."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.WebExportsExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}
tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}
sourceSets.main {
    resources.srcDir(rootProject.file("assets"))
}
tasks.register<JavaExec>("textPdf") {
    group = "application"
    description = "Generates an embedded-font Chinese and Latin paragraph example."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.TextPdfExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("flowPdf") {
    group = "application"
    description = "Generates a report from semantic paragraphs with automatic pagination."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.FlowPdfExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("tablePdf") {
    group = "application"
    description = "Generates a mixed paragraph/table report with repeated headers and whole-row pagination."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.TablePdfExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("componentPdf") {
    group = "application"
    description = "Generates composed pages with contents, bookmarks, annotations and editable forms."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.ComponentPdfExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("advancedPdf") {
    group = "application"
    description = "Generates rich text, section, merged-grid, nested-flow and Unicode-form fixtures."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.AdvancedPdfExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Runs a warmup and three bounded large-document layout measurements."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.BenchmarkExample")
    workingDir = rootProject.projectDir
    maxHeapSize = "1g"
    args(providers.gradleProperty("benchmarkBlocks").orElse("5000").get())
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("production") {
    group = "application"
    description = "Generates shaped text, columns, float, split-table, continued-note and multi-format fixtures."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.ProductionExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("capabilities") {
    group = "application"
    description = "Generates shared shaping, templates, rich cells, hyperlinks, CFF, balanced columns and encrypted PDF fixtures."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.CapabilitiesExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
    if (providers.gradleProperty("signerOpenSsl").isPresent) {
        args(providers.gradleProperty("signerOpenSsl").get(), providers.gradleProperty("signerCertificate").get(), providers.gradleProperty("signerKey").get())
    }
}

tasks.register<JavaExec>("inlineImages") {
    group = "application"
    description = "Generates paginated inline PNG/JPEG, bidi, linked images, split cells and DOCX roundtrip fixtures."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.InlineImagesExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("multiFloats") {
    group = "application"
    description = "Generates staggered edge floats, blocked line bands, whole-group page moves and static previews."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.MultiFloatExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("continuousColumns") {
    group = "application"
    description = "Generates remaining-height text columns, full continuations, three columns and shaped text."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.ContinuousColumnsExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("mixedColumns") {
    group = "application"
    description = "Generates continuous mixed columns with images, nested flow, repeated headers and merged row groups."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.MixedColumnsExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("inlineFields") {
    group = "application"
    description = "Generates final-page inline fields, references, footnote anchors and bidi/image fixtures."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.InlineFieldsExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("docxFields") {
    group = "application"
    description = "Generates native Word page and reference fields plus a strict semantic roundtrip PDF."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.DocxFieldsExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("authoring") {
    group = "application"
    description = "Generates short authoring API, rich headings/lists and justified Chinese/OpenType fixtures."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.AuthoringExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("stressReports") {
    group = "verification"
    description = "Runs six bounded mixed reports with two workers and atomic failure recovery."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.ReportStressExample")
    workingDir = rootProject.projectDir
    maxHeapSize = "512m"
    systemProperty("java.awt.headless", "true")
    systemProperty("export4j.stress.run", providers.gradleProperty("export4jStressRun").orElse("manual").get())
}

tasks.register<JavaExec>("adrForm") {
    group = "application"
    description = "Probes dense merged form authoring, static check marks and long-cell pagination using fictional data."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.AdrFormExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("gridContinuation") {
    group = "application"
    description = "Generates complete-line merged cells, mixed images, columns and ordinary nested text fixtures."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.GridContinuationExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("staticForms") {
    group = "application"
    description = "Generates font-independent static marks, choices, label values and writing lines."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.StaticFormsExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("semanticTables") {
    group = "application"
    description = "Generates nested semantic tables with final fields, source cells and translated hyperlinks."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.SemanticTableExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("semanticTableFlow") {
    group = "application"
    description = "Generates semantic tables across short regions, columns and complete-line cell continuations."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.SemanticTableFlowExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("styleDefaults") {
    group = "application"
    description = "Generates inherited nested styles, explicit overrides and merged-cell complete-line continuation."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.StyleDefaultsExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("gridSemanticFlow") {
    group = "application"
    description = "Generates rich semantic tables inside merged cells with repeated fields and complete-line continuation."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.GridSemanticFlowExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("xmlTemplates") {
    group = "application"
    description = "Generates a strict offline declarative report with binding, repeated rows and explicit images."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.XmlTemplateExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("textCorpus") {
    group = "application"
    description = "Generates fixed-font script, cluster and bidi fixtures for independent reader checks."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.TextCorpusExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("markdownTemplates") {
    group = "application"
    description = "Generates a strict offline Markdown summary with literal binding and explicit images."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.MarkdownTemplateExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("pdfOperations") {
    group = "application"
    description = "Generates strict static PDF inspection, reordered selection, split and mixed-page merge fixtures."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.PdfOperationsExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("xmlReferences") {
    group = "application"
    description = "Generates declarative final fields, numbered references, passive links and native DOCX fields."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.XmlReferencesExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("propertyStyles") {
    group = "application"
    description = "Generates partial text style overrides, nested defaults, merged cells and short regions."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.PropertyStylesExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("streamingTable") {
    group = "application"
    description = "Generates lazy fixed-column table, repeated headers and empty-source PDF fixtures."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.StreamingTableExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register("writeStreamingTableClasspath") {
    group = "verification"
    description = "Compiles and records the runtime needed for independently sampled streaming-table JVMs."
    dependsOn(tasks.named("classes"))
    dependsOn(sourceSets.main.get().runtimeClasspath)
    doLast {
        val directory = rootProject.file("build/verification/streaming-table")
        directory.mkdirs()
        directory.resolve("runtime-classpath.txt").writeText(sourceSets.main.get().runtimeClasspath.asPath + "\n")
        val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }.get()
        directory.resolve("java-executable.txt").writeText(launcher.executablePath.asFile.absolutePath + "\n")
    }
}

tasks.register<JavaExec>("columnSpans") {
    group = "application"
    description = "Generates semantic column spans, complete-line splits, short columns, template bindings and native DOCX gridSpan."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.ColumnSpanExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("rowSpans") {
    group = "application"
    description = "Generates semantic two-dimensional spans, atomic row groups, short columns, static bindings and standard DOCX vMerge."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.RowSpanExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("blockCells") {
    group = "application"
    description = "Generates ordered Paragraph/Table/Paragraph cells, atomic short regions, independent merged grids and standard nested DOCX."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.BlockCellsExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("xmlBlockCells") {
    group = "application"
    description = "Generates readable XML block cells, scoped Each/Rows references, atomic merged grids and native nested DOCX."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.XmlBlockCellsExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("streamingRichText") {
    group = "application"
    description = "Generates bounded rich streaming rows, explicit physical font roles, decorations and complex-script diagnostics."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.StreamingRichTextExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("themes") {
    group = "application"
    description = "Generates authoring theme snapshots, named styles, explicit overrides and default-compatible reports."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.ThemeExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("xmlThemes") {
    group = "application"
    description = "Generates shared XML structures with complete themes and static named paragraph/inline styles."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.XmlThemeExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("formStyles") {
    group = "application"
    description = "Generates editable text, choice and mark fields with explicit appearance styles and legacy defaults."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.FormStyleExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("xmlIncludes") {
    group = "application"
    description = "Generates explicitly registered offline XML fragments with isolated IDs, lists and copied references."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.XmlIncludeExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("contentsStyles") {
    group = "application"
    description = "Generates independent contents title, entry and page-label typography and final navigation."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.ContentsStyleExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("xmlNumbering") {
    group = "application"
    description = "Generates multilevel XML lists, copied full-number references and native DOCX numbering."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.XmlNumberingExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("xmlPageSettings") {
    group = "application"
    description = "Generates XML paper presets, absolute lengths, asymmetric margins and native page settings."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.XmlPageSettingsExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("pageRegionFonts") {
    group = "application"
    description = "Generates all fixed page regions with explicit font roles, nested scopes and final section numbering."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.PageRegionFontsExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("componentText") {
    group = "application"
    description = "Generates standard fixed rich text and reusable named font scopes."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.ComponentTextExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("listMarkers") {
    group = "application"
    description = "Generates independently styled list markers and native numbering run properties."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.ListMarkerExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("xmlTextDefaults") {
    group = "application"
    description = "Generates XML default font roles, colors, resets, fields and nested cell scopes."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.XmlTextDefaultsExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("xmlPageRegions") {
    group = "application"
    description = "Generates themed XML headers/footers, reserved body space and final page fields."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.XmlPageRegionsExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}


tasks.register<JavaExec>("xmlSections") {
    group = "application"
    description = "Generates XML sections, repeated image/table bands and native DOCX page regions."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.XmlSectionsExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("recursiveTables") {
    group = "application"
    description = "Generates recursive semantic tables and vertical continuations in pages and columns."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.RecursiveTablesExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("recursiveIncludes") {
    group = "application"
    description = "Generates recursive offline fragments, scoped references and data-bound links."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.RecursiveIncludesExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("variableColumns") {
    group = "application"
    description = "Generates unequal continuous columns with rich, complex and mixed text flow."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.VariableColumnsExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("themeAppearance") {
    group = "application"
    description = "Generates resolved global, domain and local font/color theme defaults."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.ThemeAppearanceExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("pageVariants") {
    group = "application"
    description = "Generates semantic first/even page bands and a native DOCX round trip."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.PageVariantsExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("pageOverlays") {
    group = "application"
    description = "Generates semantic XML page overlays, final fields and explicit target losses."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.PageOverlaysExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("minimumLines") {
    group = "application"
    description = "Generates unequal columns with bounded orphan/widow reflow and short-region skips."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.MinimumLinesExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("streamingColumnSpans") {
    group = "application"
    description = "Generates single-pass horizontal spans with physical anchor columns and repeating headers."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.StreamingColumnSpansExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("streamingMedia") {
    group = "application"
    description = "Generates single-pass PNG/JPEG cells and passive text/image hyperlinks."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.StreamingMediaExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("advancedTables") {
    group = "application"
    description = "Generates automatic-width merged owners and single-pass complete-line continuations."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.AdvancedTablesExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("pdfCertification") {
    group = "application"
    description = "Generates DocMDP certification fixtures using explicitly supplied offline signing material."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.PdfCertificationExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
    args(providers.gradleProperty("signingOpenSsl").orElse("openssl").get(),
         providers.gradleProperty("signingCertificate").orElse("build/verification/signing/dev-cert.pem").get(),
         providers.gradleProperty("signingKey").orElse("build/verification/signing/dev-key.pem").get())
}

tasks.register<JavaExec>("streamingProtection") {
    group = "application"
    description = "Generates single-pass AES-256 and static PDF/A-2b table fixtures."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.StreamingProtectionExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("variableTables") {
    group = "application"
    description = "Generates semantic tables reflowed across unequal-width columns."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.VariableTablesExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("verifyPdfSignatures") {
    group = "verification"
    description = "Verifies exact signed revisions through an explicitly supplied offline CMS verifier."
    dependsOn("pdfCertification")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.PdfSignatureVerificationExample")
    workingDir = rootProject.projectDir
    args(providers.gradleProperty("signingOpenSsl").orElse("openssl").get())
}

tasks.register<JavaExec>("streamingFields") {
    group = "application"
    description = "Generates actual presentation PAGE fields and verified caller-declared total fields."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.StreamingFieldsExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("nestedAutomaticTables") {
    group = "application"
    description = "Generates nested inline tables with bounded automatic columns at actual parent widths."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.mirakyux.export4j.examples.NestedAutomaticTablesExample")
    workingDir = rootProject.projectDir
    systemProperty("java.awt.headless", "true")
}
