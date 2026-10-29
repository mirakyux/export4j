// SPDX-License-Identifier: Apache-2.0
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { maven { url = file(providers.gradleProperty("export4jRepositoryDirectory").orElse("../../build/repository").get()).toURI() } }
            filter { includeGroup("com.mirakyux.export4j") }
        }
        mavenCentral()
    }
}
rootProject.name = "isolated-library-consumer"
