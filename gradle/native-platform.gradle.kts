// SPDX-License-Identifier: Apache-2.0
// Shared by the library tests and the independent Maven consumer; never downloaded at runtime.
val supportedNativeClassifiers = setOf(
    "natives-windows", "natives-windows-arm64", "natives-windows-x86",
    "natives-linux", "natives-linux-arm64", "natives-linux-arm32",
    "natives-linux-ppc64le", "natives-linux-riscv64", "natives-freebsd",
    "natives-macos", "natives-macos-arm64"
)
val selectedNativeClassifier = providers.gradleProperty("export4jNativeClassifier").orElse(provider {
    val operatingSystem = System.getProperty("os.name")
    val architecture = System.getProperty("os.arch").lowercase()
    when {
        operatingSystem.startsWith("Windows") -> when (architecture) {
            "amd64", "x86_64" -> "natives-windows"
            "aarch64", "arm64" -> "natives-windows-arm64"
            "x86", "i386" -> "natives-windows-x86"
            else -> error("Unsupported Windows native architecture; set export4jNativeClassifier explicitly")
        }
        operatingSystem.startsWith("Mac") -> when (architecture) {
            "amd64", "x86_64" -> "natives-macos"
            "aarch64", "arm64" -> "natives-macos-arm64"
            else -> error("Unsupported macOS native architecture; set export4jNativeClassifier explicitly")
        }
        operatingSystem.startsWith("Linux") -> when (architecture) {
            "amd64", "x86_64" -> "natives-linux"
            "aarch64", "arm64" -> "natives-linux-arm64"
            "arm", "arm32" -> "natives-linux-arm32"
            "ppc64le" -> "natives-linux-ppc64le"
            "riscv64" -> "natives-linux-riscv64"
            else -> error("Unsupported Linux native architecture; set export4jNativeClassifier explicitly")
        }
        else -> error("Unsupported native platform; set export4jNativeClassifier explicitly")
    }
}).get()
require(selectedNativeClassifier in supportedNativeClassifiers) { "Unknown export4jNativeClassifier" }
extra["export4jNativeClassifierResolved"] = selectedNativeClassifier
