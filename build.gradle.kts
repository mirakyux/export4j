// SPDX-License-Identifier: Apache-2.0
plugins {
    base
}

val junitBom = libs.junit.bom
val junitJupiter = libs.junit.jupiter
val junitLauncher = libs.junit.launcher

allprojects {
    group = "com.mirakyux.export4j"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "maven-publish")
    if (name == "export4j-bom") {
        apply(plugin = "java-platform")
    } else {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
        withSourcesJar()
        withJavadocJar()
    }
    dependencies {
        "testImplementation"(platform(junitBom))
        "testImplementation"(junitJupiter)
        "testRuntimeOnly"(junitLauncher)
    }
    dependencyLocking {
        lockAllConfigurations()
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(21)
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }
    tasks.withType<Javadoc>().configureEach {
        (options as StandardJavadocDocletOptions).apply {
            encoding = "UTF-8"
            addStringOption("Xdoclint:all,-missing", "-quiet")
        }
    }
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("-Djava.awt.headless=true")
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
    tasks.withType<Jar>().configureEach {
        from(rootProject.file("LICENSE")) { into("META-INF") }
        from(rootProject.file("NOTICE")) { into("META-INF") }
        from(rootProject.file("third-party/licenses")) { into("META-INF/third-party") }
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    }
    if (name != "examples") {
        extensions.configure<PublishingExtension> {
            publications {
                create<MavenPublication>("library") {
                    from(components[if (project.name == "export4j-bom") "javaPlatform" else "java"])
                    pom {
                        name.set(project.name)
                        description.set(provider { project.description ?: project.name })
                        licenses { license { name.set("Apache License, Version 2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0") } }
                    }
                }
            }
            repositories { maven { name = "isolated"; url = rootProject.layout.buildDirectory.dir("repository").get().asFile.toURI() } }
        }
    }
}

tasks.named("check") {
    dependsOn(subprojects.map { "${it.path}:check" })
}
tasks.named("assemble") {
    dependsOn(subprojects.map { "${it.path}:assemble" })
}
tasks.register("test") {
    group = "verification"
    description = "Runs all module tests."
    dependsOn(subprojects.filter { it.name != "export4j-bom" }.map { "${it.path}:test" })
}

tasks.register("publishLocalBundle") {
    group = "publishing"
    description = "Publishes all libraries and the BOM to build/repository, without remote access or user Maven writes."
    dependsOn(subprojects.filter { it.name != "examples" }.map { "${it.path}:publishLibraryPublicationToIsolatedRepository" })
}

val inventoryLibraries = subprojects.filter { it.name != "examples" && it.name != "export4j-bom" }
val reviewedRuntimeLicenses = mapOf(
    "org.jsoup:jsoup:1.21.2" to "MIT",
    "org.apache.pdfbox:pdfbox:3.0.8" to "Apache-2.0",
    "org.apache.pdfbox:fontbox:3.0.8" to "Apache-2.0",
    "org.apache.pdfbox:pdfbox-io:3.0.8" to "Apache-2.0",
    "commons-logging:commons-logging:1.4.0" to "Apache-2.0",
    "org.commonmark:commonmark:0.24.0" to "BSD-2-Clause",
    "org.commonmark:commonmark-ext-gfm-tables:0.24.0" to "BSD-2-Clause",
    "org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0" to "BSD-2-Clause",
    "org.bouncycastle:bcpkix-jdk18on:1.86" to "MIT",
    "org.bouncycastle:bcprov-jdk18on:1.86" to "MIT",
    "org.bouncycastle:bcutil-jdk18on:1.86" to "MIT",
    "org.lwjgl:lwjgl:3.4.3" to "BSD-3-Clause",
    "org.lwjgl:lwjgl-harfbuzz:3.4.3" to "BSD-3-Clause"
)
fun jsonQuoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
for (library in inventoryLibraries) {
    library.tasks.register("runtimeInventory") {
        group = "distribution"
        dependsOn("jar")
        val runtime = library.configurations.named("runtimeClasspath")
        inputs.files(runtime)
        inputs.file(library.tasks.named<Jar>("jar").flatMap { it.archiveFile })
        val target = library.layout.buildDirectory.file("reports/runtime-components.json")
        outputs.file(target)
        doLast {
            val entries = sortedMapOf<String, String>()
            fun component(group: String, name: String, version: String, file: File, licenseId: String = "Apache-2.0"): String {
                val purl = "pkg:maven/$group/$name@$version"
                val sha = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                return "{\"type\":\"library\",\"bom-ref\":${jsonQuoted(purl)},\"group\":${jsonQuoted(group)},\"name\":${jsonQuoted(name)},\"version\":${jsonQuoted(version)},\"purl\":${jsonQuoted(purl)},\"hashes\":[{\"alg\":\"SHA-256\",\"content\":\"$sha\"}],\"licenses\":[{\"license\":{\"id\":${jsonQuoted(licenseId)}}}]}"
            }
            val archive = library.tasks.named<Jar>("jar").get().archiveFile.get().asFile
            val own = "${library.group}:${library.name}:${library.version}"
            entries[own] = component(library.group.toString(), library.name, library.version.toString(), archive)
            for (artifact in runtime.get().resolvedConfiguration.resolvedArtifacts) {
                val id = artifact.moduleVersion.id
                if (id.group == library.group) continue
                val key = "${id.group}:${id.name}:${id.version}"
                val licenseId = reviewedRuntimeLicenses[key] ?: error("Runtime artifact needs a reviewed SBOM license record: $key")
                entries[key] = component(id.group, id.name, id.version, artifact.file, licenseId)
            }
            target.get().asFile.apply { parentFile.mkdirs(); writeText(entries.values.joinToString("\n"), Charsets.UTF_8) }
        }
    }
}
tasks.register("sbom") {
    group = "distribution"
    description = "Generates a CycloneDX JSON inventory of actual library runtime artifacts."
    dependsOn(inventoryLibraries.map { "${it.path}:runtimeInventory" })
    val inventories = inventoryLibraries.map { it.layout.buildDirectory.file("reports/runtime-components.json") }
    inputs.files(inventories)
    outputs.file(layout.buildDirectory.file("reports/sbom.cdx.json"))
    doLast {
        val components = inventories.flatMap { it.get().asFile.readLines(Charsets.UTF_8) }.filter { it.isNotBlank() }.distinct().sorted()
        val target = layout.buildDirectory.file("reports/sbom.cdx.json").get().asFile
        target.parentFile.mkdirs()
        target.writeText("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"version\":1,\"components\":[${components.joinToString(",")}]}\n", Charsets.UTF_8)
    }
}
