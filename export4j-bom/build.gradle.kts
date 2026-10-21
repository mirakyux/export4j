// SPDX-License-Identifier: Apache-2.0
description = "Aligned versions for the document engine modules"
dependencies {
    constraints {
        rootProject.subprojects.filter { it.name != "examples" && it.name != "export4j-bom" }.forEach { api(project(it.path)) }
    }
}
