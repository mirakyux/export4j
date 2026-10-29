#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Conservative Java declaration guard; behavioral and reader compatibility need separate evidence."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile


def jdk_tool(name: str) -> str:
    suffix = ".exe" if os.name == "nt" else ""
    configured = Path(os.environ.get("JAVA_HOME", "")) / "bin" / (name + suffix)
    found = str(configured) if configured.is_file() else shutil.which(name)
    if not found:
        raise RuntimeError(f"Java 21 tool required: {name}")
    return found


def run(arguments: list[str]) -> str:
    process = subprocess.run(arguments, check=True, capture_output=True, text=True,
                             encoding="utf-8", errors="strict", timeout=180)
    return process.stdout.replace("\r\n", "\n")


def snapshot_jar(archive: Path) -> dict:
    with zipfile.ZipFile(archive) as jar:
        names = sorted(name[:-6].replace("/", ".") for name in jar.namelist()
                       if name.endswith(".class") and not name.startswith("META-INF/")
                       and not name.endswith(("module-info.class", "package-info.class")))
    result = {}
    for offset in range(0, len(names), 48):
        output = run([jdk_tool("javap"), "-J-Duser.language=en", "-protected", "-s", "-constants",
                      "-classpath", str(archive), *names[offset:offset + 48]])
        current = None
        declaration = None
        for line in output.splitlines():
            stripped = line.strip()
            if line and not line[0].isspace() and line.endswith("{"):
                match = re.search(r"\b(?:class|interface|enum)\s+([^\s<{]+)", line)
                current = match.group(1) if match and line.startswith(("public ", "protected ")) else None
                if current:
                    result[current] = {"declaration": line[:-1].strip(), "members": {}}
            elif current and stripped.startswith(("public ", "protected ")):
                declaration = stripped
            elif current and stripped.startswith("descriptor:") and declaration:
                descriptor = stripped.removeprefix("descriptor:").strip()
                # A JVM descriptor alone does not identify overloads with different names.
                prefix = declaration.split("(", 1)[0] if "(" in declaration else declaration.split(" = ", 1)[0].rstrip(";")
                name = prefix.rsplit(" ", 1)[-1]
                key = name + " " + descriptor
                result[current]["members"][key] = declaration
                declaration = None
            elif stripped == "}":
                current = declaration = None
    return result


def snapshot(root: Path, reference: str, version: str = "0.1.0-SNAPSHOT") -> dict:
    modules = {}
    for module in sorted(root.glob("export4j-*")):
        if not module.is_dir() or module.name == "export4j-bom":
            continue
        archives = list((module / "build/libs").glob(module.name + "-" + version + ".jar"))
        if len(archives) != 1:
            raise RuntimeError(f"Assemble exactly one candidate JAR for {module.name}; found {len(archives)}")
        modules[module.name] = snapshot_jar(archives[0])
    if len(modules) != 16 or not all(modules.values()):
        raise RuntimeError("Expected the complete 16-library API, without empty modules")
    return {"schema": 1, "reference": reference, "jdk": "21", "modules": modules}


def compare(baseline: dict, candidate: dict) -> dict:
    if baseline.get("schema") != 1 or candidate.get("schema") != 1:
        raise ValueError("Unsupported API snapshot schema")
    breaks, additions = [], []
    foreign = []
    for module, classes in candidate["modules"].items():
        for name, item in classes.items():
            for declaration in (item["declaration"], *item["members"].values()):
                # Values of String constants are data, rather than exposed Java types.
                types_only = re.sub(r'"(?:\\.|[^"\\])*"', '""', declaration)
                for type_name in re.findall(r"(?:[a-z_]\w*\.)+[A-Z]\w*(?:\$\w+)*", types_only):
                    if not type_name.startswith(("com.mirakyux.export4j.", "java.", "javax.",
                                                 "org.w3c.dom.", "org.xml.sax.", "org.ietf.jgss.")):
                        foreign.append({"api": module + ":" + name, "type": type_name})
    for module, classes in baseline["modules"].items():
        current = candidate["modules"].get(module, {})
        for name, previous in classes.items():
            location = module + ":" + name
            if name not in current:
                breaks.append({"api": location, "reason": "type removed or no longer accessible"})
                continue
            actual = current[name]
            if actual["declaration"] != previous["declaration"]:
                breaks.append({"api": location, "reason": "type declaration changed",
                               "before": previous["declaration"], "after": actual["declaration"]})
            for key, declaration in previous["members"].items():
                if actual["members"].get(key) != declaration:
                    breaks.append({"api": location + "#" + key, "reason": "member removed or declaration/constant changed",
                                   "before": declaration, "after": actual["members"].get(key)})
            for key in sorted(actual["members"].keys() - previous["members"].keys()):
                declaration = actual["members"][key]
                item = {"api": location + "#" + key, "declaration": declaration}
                # Existing client implementors/subclasses cannot acquire a newly required abstract method.
                (breaks if " abstract " in declaration else additions).append(item)
        for name in sorted(current.keys() - classes.keys()):
            additions.append({"api": module + ":" + name, "reason": "type added"})
    for module in sorted(candidate["modules"].keys() - baseline["modules"].keys()):
        additions.append({"api": module, "reason": "module added"})
    return {"schema": 1, "baseline": baseline["reference"], "candidate": candidate["reference"],
            "compatible": not breaks and not foreign, "breaking": breaks, "additions": additions,
            "foreignApiTypes": foreign,
            "scope": "public/protected JVM descriptors, generic/throws declarations, inheritance, modifiers and constants; behavior excluded"}


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    take = commands.add_parser("snapshot")
    take.add_argument("--root", type=Path, default=Path("."))
    take.add_argument("--reference", default="candidate")
    take.add_argument("--version", default="0.1.0-SNAPSHOT")
    take.add_argument("--output", type=Path, required=True)
    check = commands.add_parser("compare")
    check.add_argument("--baseline", type=Path, required=True)
    check.add_argument("--candidate", type=Path, required=True)
    check.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "snapshot":
        value = snapshot(args.root, args.reference, args.version)
        write_json(args.output, value)
        count = sum(len(classes) for classes in value["modules"].values())
        print(f"API snapshot: {len(value['modules'])} modules, {count} accessible types")
        return 0
    result = compare(json.loads(args.baseline.read_text(encoding="utf-8")),
                     json.loads(args.candidate.read_text(encoding="utf-8")))
    write_json(args.report, result)
    print(f"API comparison: {len(result['breaking'])} incompatible declarations, {len(result['additions'])} additions, {len(result['foreignApiTypes'])} foreign API types")
    return 0 if result["compatible"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
