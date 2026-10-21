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
}
application {
    mainClass.set("com.mirakyux.export4j.examples.PdfExample")
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
