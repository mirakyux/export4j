// SPDX-License-Identifier: Apache-2.0
import java.security.MessageDigest

plugins { application }
apply(from = "../../gradle/native-platform.gradle.kts")
layout.buildDirectory.set(layout.projectDirectory.dir(providers.gradleProperty("consumerBuildDirectory").orElse("build").get()))
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
dependencies {
    implementation(platform("com.mirakyux.export4j:export4j-bom:${providers.gradleProperty("export4jVersion").orElse("0.1.0-SNAPSHOT").get()}"))
    implementation("com.mirakyux.export4j:export4j-components")
    implementation("com.mirakyux.export4j:export4j-graphics-core")
    implementation("com.mirakyux.export4j:export4j-backend-pdf")
    implementation("com.mirakyux.export4j:export4j-backend-svg")
    implementation("com.mirakyux.export4j:export4j-backend-raster")
    implementation("com.mirakyux.export4j:export4j-adapter-docx")
    implementation("com.mirakyux.export4j:export4j-adapter-template")
    implementation("com.mirakyux.export4j:export4j-adapter-pdf")
    implementation("com.mirakyux.export4j:export4j-adapter-web")
    implementation("com.mirakyux.export4j:export4j-opentype")
    val nativeClassifier = rootProject.extra["export4jNativeClassifierResolved"] as String
    runtimeOnly("org.lwjgl:lwjgl:3.4.3:$nativeClassifier")
    runtimeOnly("org.lwjgl:lwjgl-harfbuzz:3.4.3:$nativeClassifier")
}
application { mainClass.set("consumer.Consumer") }

tasks.register<JavaExec>("openType") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.OpenTypeConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

mapOf(
    "verticalText" to "consumer.Gap08VerticalTextConsumer",
    "streamingRowSpans" to "consumer.StreamingRowSpansConsumer",
    "streamingReplay" to "consumer.StreamingReplayConsumer",
    "gap21Graphics" to "consumer.Gap21GraphicsConsumer"
).forEach { (taskName, entryPoint) ->
    tasks.register<JavaExec>(taskName) {
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set(entryPoint)
        workingDir = projectDir
        systemProperty("java.awt.headless", "true")
        systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
        if (taskName == "streamingRowSpans") { maxHeapSize = "64m" }
    }
}

tasks.register<JavaExec>("recoveryClosure") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.RecoveryClosureConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

tasks.register<JavaExec>("navigationRecovery") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.NavigationRecoveryConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

tasks.register<JavaExec>("sectionRecovery") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.SectionRecoveryConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

tasks.register<JavaExec>("lossyRecovery") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.LossyRecoveryConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}
tasks.register<JavaExec>("embeddedContainerRecovery") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.EmbeddedContainerRecoveryConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

tasks.register<JavaExec>("embeddedPngRecovery") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.EmbeddedPngRecoveryConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

tasks.register<JavaExec>("embeddedCommentRecovery") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.EmbeddedCommentRecoveryConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

tasks.register<JavaExec>("embeddedHtmlRecovery") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.EmbeddedHtmlRecoveryConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

tasks.register<JavaExec>("modelRecovery") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.ModelRecoveryConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

dependencyLocking { lockAllConfigurations() }
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}
tasks.named<JavaExec>("run") {
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}
tasks.register<JavaExec>("pdfFontCompatibility") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.PdfFontCompatibilityConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

tasks.register<JavaExec>("pdfCompatibility") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.PdfCompatibilityConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

tasks.register<JavaExec>("pdfExtendedFonts") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.PdfExtendedFontConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

tasks.register<JavaExec>("pdfFormEdit") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.PdfFormEditConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

tasks.register<JavaExec>("pdfPassive") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.PdfPassiveConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

tasks.register<JavaExec>("pdfComplexRecovery") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.PdfComplexRecoveryConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

tasks.register<JavaExec>("pdfFormImport") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.PdfFormImportConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

tasks.register<JavaExec>("xmlCrossReferenceSaving") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.XmlCrossReferenceSavingConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

tasks.register<JavaExec>("xmlSemanticSaving") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.XmlSemanticSavingConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

tasks.register<JavaExec>("xmlRegionSaving") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.XmlRegionSavingConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

tasks.register<JavaExec>("xmlModelSaving") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.XmlModelSavingConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

tasks.register<JavaExec>("xmlTemplateBundle") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.XmlTemplateBundleConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}
for ((taskName, className) in mapOf(
    "pdfInstanceEditing" to "consumer.PdfInstanceEditingConsumer",
    "pdfAutomaticRecovery" to "consumer.PdfAutomaticRecoveryConsumer",
    "pdfExtendedForms" to "consumer.PdfExtendedFormsConsumer",
    "pdfStandards" to "consumer.PdfStandardsConsumer",
    "pdfSelectiveRedaction" to "consumer.PdfSelectiveRedactionConsumer"
)) {
    tasks.register<JavaExec>(taskName) {
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set(className)
        workingDir = projectDir
        systemProperty("java.awt.headless", "true")
        systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
        if (taskName == "pdfStandards") {
            providers.gradleProperty("consumer.verificationPython").orNull?.let {
                systemProperty("consumer.verificationPython", it)
            }
        }
    }
}

tasks.register("resolvedLibraries") {
    val runtime = configurations.named("runtimeClasspath")
    val selectedComponents = runtime.map { configuration ->
        configuration.incoming.resolutionResult.allComponents.mapNotNull { component ->
            (component.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.let {
                "${it.group}:${it.module}:${it.version}"
            }
        }.distinct().sorted()
    }
    inputs.files(runtime)
    inputs.property("selectedComponents", selectedComponents)
    outputs.file(layout.buildDirectory.file("resolved-libraries.txt"))
    outputs.file(layout.buildDirectory.file("resolved-components.txt"))
    outputs.file(layout.buildDirectory.file("resolved-library-sha256.txt"))
    doLast {
        val artifacts = runtime.get().resolvedConfiguration.resolvedArtifacts
        val ids = artifacts.map { it.moduleVersion.id.toString() }.distinct().sorted()
        layout.buildDirectory.file("resolved-libraries.txt").get().asFile.writeText(ids.joinToString("\n") + "\n")
        layout.buildDirectory.file("resolved-components.txt").get().asFile.writeText(selectedComponents.get().joinToString("\n") + "\n")
        val hashes = artifacts.map { artifact ->
            val sha = MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes())
                .joinToString("") { "%02x".format(it) }
            "${artifact.moduleVersion.id}\t${artifact.file.name}\t$sha"
        }.sorted()
        layout.buildDirectory.file("resolved-library-sha256.txt").get().asFile.writeText(hashes.joinToString("\n") + "\n")
    }
}
// Named isolated acceptance entry point for recursive, grid and contour continuation.
tasks.register<JavaExec>("complexFlows") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.Gap03ComplexFlowConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

tasks.register<JavaExec>("gap21Complete") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("consumer.Gap21CompleteConsumer")
    workingDir = projectDir
    systemProperty("java.awt.headless", "true")
    systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
}

for ((taskName, className) in listOf(
    "docxSemanticComplete" to "consumer.DocxSemanticCompleteConsumer",
    "htmlCssLayout" to "consumer.HtmlCssLayoutConsumer"
)) {
    tasks.register<JavaExec>(taskName) {
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set(className)
        workingDir = projectDir
        systemProperty("java.awt.headless", "true")
        systemProperty("consumer.outputDirectory", layout.buildDirectory.dir("output").get().asFile.absolutePath)
    }
}
