# SPDX-License-Identifier: Apache-2.0
"""Run published consumers with every JDK printing class denied at load time.

This verifier never changes installed printers, drivers or desktop services.
It requires a previously built isolated consumer distribution and JDK 21.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
AGENT = r'''
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.concurrent.ConcurrentSkipListSet;

public final class DenyPrinting {
    private static boolean printing(String name) {
        return name.startsWith("java/awt/print/")
            || name.startsWith("javax/print/") || name.startsWith("sun/print/");
    }
    public static void premain(String report, Instrumentation instrumentation) {
        var denied = new ConcurrentSkipListSet<String>();
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (printing(type.getName().replace('.', '/'))) {
                throw new IllegalStateException("Printing was already loaded: " + type.getName());
            }
        }
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name,
                    Class<?> redefining, ProtectionDomain domain, byte[] bytes) {
                if (name != null && printing(name)) {
                    denied.add(name);
                    // Returning invalid bytes makes the VM reject the class.
                    // Throwing from a transformer would be ignored by the VM.
                    return new byte[]{0};
                }
                return null;
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { Files.write(Path.of(report), denied); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }, "printing-denial-report"));
    }
}
'''
CONTROL = r'''
public final class PrintingControl {
    public static void main(String[] arguments) throws Exception {
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("A headless JVM is required");
        }
        for (String name : new String[]{"java.awt.print.PrinterJob",
                "javax.print.PrintServiceLookup", "sun.print.PrintJob2D"}) {
            try {
                Class.forName(name);
                throw new IllegalStateException("Printing class was allowed: " + name);
            } catch (ClassFormatError expected) {
                System.out.println("DENIED " + name);
            }
        }
    }
}
'''


def run(command, output, *, cwd=ROOT, timeout=120):
    result = subprocess.run(command, cwd=cwd, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, timeout=timeout, check=False)
    output.write_text(result.stdout, encoding="utf-8")
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}); see {output}")
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--consumer-build-directory", type=Path, required=True)
    parser.add_argument("--output-directory", type=Path, required=True)
    args = parser.parse_args()
    consumer = args.consumer_build_directory.resolve()
    output = args.output_directory.resolve()
    output.mkdir(parents=True, exist_ok=False)
    java = shutil.which("java")
    if not java:
        raise RuntimeError("JDK 21 java executable not found")
    java_bin = Path(java).parent
    suffix = ".exe" if os.name == "nt" else ""
    source = output / "agent"
    source.mkdir()
    (source / "DenyPrinting.java").write_text(AGENT, encoding="utf-8")
    (source / "PrintingControl.java").write_text(CONTROL, encoding="utf-8")
    run([str(java_bin / ("javac" + suffix)), "--release", "21", "-d", str(source),
         str(source / "DenyPrinting.java"), str(source / "PrintingControl.java")], output / "compile.log")
    agent = output / "deny-printing.jar"
    with zipfile.ZipFile(agent, "w") as archive:
        archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nPremain-Class: DenyPrinting\n\n")
        for path in sorted(source.glob("*.class")):
            archive.write(path, path.name)
    distributions = list((consumer / "distributions").glob("*.zip"))
    if len(distributions) != 1:
        raise RuntimeError("Expected exactly one isolated consumer distZip")
    runtime = output / "runtime"
    runtime.mkdir()
    with zipfile.ZipFile(distributions[0]) as archive:
        for member in archive.infolist():
            parts = Path(member.filename).parts
            if len(parts) == 3 and parts[1] == "lib" and parts[2].endswith(".jar"):
                (runtime / parts[2]).write_bytes(archive.read(member))
    jars = sorted(runtime.glob("*.jar"))
    project_jars = [path for path in jars if path.name.startswith("export4j-")]
    if len(project_jars) != 16 or not any(path.name == "isolated-library-consumer.jar" for path in jars):
        raise RuntimeError("Incomplete published library or consumer JAR distribution")
    resolved = {}
    for line in (consumer / "resolved-library-sha256.txt").read_text(encoding="utf-8").splitlines():
        _, name, digest = line.split("\t")
        resolved[name] = digest
    forbidden = (b"java/awt/print/", b"javax/print/", b"sun/print/")
    for path in jars:
        if path.name != "isolated-library-consumer.jar":
            if resolved.get(path.name) != hashlib.sha256(path.read_bytes()).hexdigest():
                raise AssertionError(f"Distribution JAR differs from resolved artifact: {path.name}")
        if path in project_jars:
            with zipfile.ZipFile(path) as archive:
                for name in archive.namelist():
                    if name.endswith(".class") and any(reference in archive.read(name) for reference in forbidden):
                        raise AssertionError(f"Production bytecode references printing: {path.name}: {name}")
    classpath = os.pathsep.join(str(path) for path in jars)
    control_log = output / "negative-control.denied"
    stdout = run([java, "-Djava.awt.headless=true", f"-javaagent:{agent}={control_log}",
                  "-cp", str(source), "PrintingControl"], output / "negative-control.log")
    expected = {"java/awt/print/PrinterJob", "javax/print/PrintServiceLookup", "sun/print/PrintJob2D"}
    if set(control_log.read_text(encoding="utf-8").splitlines()) != expected or stdout.count("DENIED ") != 3:
        raise AssertionError("The printing denial negative control did not work")
    report = {"headless": True, "printingClassLoadingDenied": True,
              "negativeControls": 3, "publishedProjectJars": 16,
              "resolvedRuntimeHashesVerified": len(jars) - 1,
              "productionPrintingReferences": 0, "consumers": {},
              "runtimeSha256": {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in jars}}
    for name in ("Consumer", "DocxSemanticCompleteConsumer", "HtmlCssLayoutConsumer"):
        denied = output / (name + ".denied")
        products = output / name
        run([java, "-Djava.awt.headless=true", f"-javaagent:{agent}={denied}",
             f"-Dconsumer.outputDirectory={products}", "-cp", classpath, "consumer." + name],
            output / (name + ".log"), cwd=ROOT / "verification/consumer", timeout=180)
        if not denied.is_file() or denied.read_text(encoding="utf-8").strip():
            raise AssertionError(f"{name} attempted to load a printing class")
        files = [path for path in products.rglob("*") if path.is_file()]
        if not files:
            raise AssertionError(f"{name} did not produce files")
        report["consumers"][name] = {"files": len(files), "printingClassesLoaded": 0,
                                      "extensions": sorted({path.suffix for path in files})}
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key != "runtimeSha256"}, indent=2))


if __name__ == "__main__":
    main()
