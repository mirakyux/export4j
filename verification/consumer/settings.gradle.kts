// SPDX-License-Identifier: Apache-2.0
val pomOnly = providers.gradleProperty("export4jPomOnly").orElse("false").get().toBooleanStrict()
val centralOnly = providers.gradleProperty("export4jCentralOnly").orElse("false").get().toBooleanStrict()
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!centralOnly) {
            exclusiveContent {
                forRepository {
                    maven {
                        url = file(providers.gradleProperty("export4jRepositoryDirectory").orElse("../../build/repository").get()).toURI()
                        if (pomOnly) { metadataSources { mavenPom(); artifact(); ignoreGradleMetadataRedirection() } }
                    }
                }
                filter { includeGroup("com.mirakyux.export4j") }
            }
        }
        mavenCentral {
            if (pomOnly) { metadataSources { mavenPom(); artifact(); ignoreGradleMetadataRedirection() } }
        }
    }
}
rootProject.name = "isolated-library-consumer"
