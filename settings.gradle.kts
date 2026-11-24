// SPDX-License-Identifier: Apache-2.0
pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "export4j"
include("export4j-model", "export4j-graphics-api", "export4j-graphics-core",
        "export4j-backend-pdf", "export4j-font-core", "export4j-font-fontbox", "export4j-text-core",
        "export4j-layout-model", "export4j-layout-core", "export4j-components", "export4j-backend-svg",
        "export4j-backend-raster", "export4j-adapter-docx", "export4j-adapter-template", "export4j-adapter-pdf",
        "export4j-adapter-web", "export4j-web-layout", "export4j-pdf-signatures", "export4j-pdf-recovery",
        "export4j-opentype", "export4j-opentype-layout", "export4j-math-api", "export4j-math-core", "export4j-math-web", "export4j-math-docx", "export4j-math-docx-web", "export4j-math",
        "export4j-starter", "export4j-starter-pdf", "export4j-bom", "examples", "export4j-designer")
