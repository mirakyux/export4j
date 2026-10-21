#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Validate shared project rules with the Python standard library."""

from __future__ import annotations

import argparse
from datetime import date
import json
from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit


STATUS = {
    "implemented": "已实现",
    "proposed": "提议中",
    "rejected": "已否决",
    "archived": "已归档",
}
CLASSES = {"architecture", "bug-fix", "feature", "process", "simplification", "testing"}
SLUG = r"[a-z0-9]+(?:-[a-z0-9]+)*"
LINK = re.compile(r"\[[^\]\n]*\]\(([^)\n]+)\)")
PRIVATE_PATTERNS = (
    ("machine-path", re.compile(r"(?i)(?<![\w])(?:[a-z]:[\\/]|\\\\[a-z0-9_.-]+[\\/]|/(?:Users|home)/[^/\s]+/)")),
    ("private-key", re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----")),
    ("access-token", re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-proj-[A-Za-z0-9_-]{20,})\b")),
)


def check(root: Path) -> tuple[list[str], dict[str, int]]:
    """Return redacted diagnostics and counts; never print matched private text."""
    root = root.resolve()
    errors: list[str] = []
    contents: dict[Path, str] = {}

    def fail(path: Path, message: str, line: int | None = None) -> None:
        label = path.relative_to(root).as_posix()
        errors.append(f"{label}{':' + str(line) if line else ''}: {message}")

    required = [
        "AGENTS.md", ".agents/AGENTS.md", ".agents/README.md",
        ".agents/notes/AGENTS.md", ".agents/notes/README.md", ".agents/notes/manifest.json",
        ".agents/scripts/verify_rules.py",
    ]
    for relative in required:
        if not (root / relative).is_file():
            fail(root / relative, "required file missing")

    candidates = [root / "AGENTS.md"] + sorted(
        path for path in (root / ".agents").rglob("*")
        if path.is_file() and "__pycache__" not in path.parts
    )
    for path in candidates:
        if not path.is_file():
            continue
        if not path.resolve().is_relative_to(root):
            fail(path, "file resolves outside repository")
            continue
        try:
            raw = path.read_bytes()
            content = raw.decode("utf-8")
        except (OSError, UnicodeError):
            fail(path, "cannot read as UTF-8 text")
            continue
        contents[path] = content
        if raw.startswith(b"\xef\xbb\xbf") or b"\r" in raw:
            fail(path, "use UTF-8 without BOM and LF line endings")
        for number, line in enumerate(content.splitlines(), 1):
            for code, pattern in PRIVATE_PATTERNS:
                if pattern.search(line):
                    fail(path, f"shared content rejected ({code}); remove private value", number)
        if path.suffix != ".md":
            continue
        for match in LINK.finditer(content):
            number = content[:match.start()].count("\n") + 1
            target = match[1].strip()
            if target.startswith("<") and target.endswith(">"):
                target = target[1:-1]
            try:
                parsed = urlsplit(target)
            except ValueError:
                fail(path, "malformed link target", number)
                continue
            if parsed.scheme in {"http", "https", "mailto"}:
                continue
            if parsed.scheme or parsed.netloc or target.startswith(("/", "\\")):
                fail(path, "use a repository-relative file link", number)
                continue
            relative = unquote(parsed.path)
            if not relative:
                continue
            destination = (path.parent / relative).resolve()
            if not destination.is_relative_to(root):
                fail(path, "file link escapes repository", number)
            elif not destination.exists():
                fail(path, "file link target missing", number)

    skills = sorted(path for path in (root / ".agents/skills").glob("*") if path.is_dir())
    if not skills:
        fail(root / ".agents/skills", "no project skills found")
    for skill in skills:
        path = skill / "SKILL.md"
        content = contents.get(path, "")
        frontmatter = re.match(r"\A---\nname: ([^\n]+)\ndescription: '([^\n]+)'\n---\n", content)
        if not frontmatter:
            fail(path, "expected name and single-quoted description frontmatter")
            continue
        name, description = frontmatter.groups()
        if name != skill.name or len(name) > 64 or not re.fullmatch(SLUG, name):
            fail(path, "skill name must match directory and use a slug of at most 64 characters")
        if not description.strip() or len(description) > 1024 or any(c in description for c in "<>"):
            fail(path, "invalid skill description")
        route = f"(.agents/skills/{skill.name}/SKILL.md)"
        if route not in contents.get(root / "AGENTS.md", ""):
            fail(path, "skill needs a task route in root AGENTS.md")

    manifest_path = root / ".agents/notes/manifest.json"
    manifest = None
    try:
        manifest = json.loads(contents.get(manifest_path, ""))
    except (ValueError, TypeError):
        fail(manifest_path, "invalid manifest JSON")
    if not isinstance(manifest, dict):
        fail(manifest_path, "manifest must be an object")
    else:
        expected = {
            "schemaVersion": 1, "language": "zh-CN", "root": ".agents/notes",
            "lifecycles": list(STATUS), "classes": sorted(CLASSES),
            "noteFilePattern": "yyyy-mm-dd-topic.md",
            "verification": {"script": ".agents/scripts/verify_rules.py"},
        }
        for key, value in expected.items():
            actual = manifest.get(key)
            if key == "classes" and isinstance(actual, list) and all(isinstance(item, str) for item in actual):
                actual = sorted(actual)
            if actual != value:
                fail(manifest_path, f"invalid manifest field: {key}")

    notes_root = root / ".agents/notes"
    note_count = 0
    for lifecycle in STATUS:
        if not (notes_root / lifecycle / "AGENTS.md").is_file():
            fail(notes_root / lifecycle / "AGENTS.md", "lifecycle rule missing")
    for path, content in contents.items():
        if not path.is_relative_to(notes_root) or path.suffix != ".md":
            continue
        relative = path.relative_to(notes_root)
        if len(relative.parts) == 1 and path.name in {"AGENTS.md", "README.md"}:
            continue
        if len(relative.parts) == 2 and relative.parts[0] in STATUS and path.name == "AGENTS.md":
            continue
        if len(relative.parts) != 3 or relative.parts[0] not in STATUS or relative.parts[1] not in CLASSES:
            fail(path, "expected lifecycle/class/yyyy-mm-dd-topic.md")
            continue
        note_count += 1
        lifecycle = relative.parts[0]
        filename = re.fullmatch(r"(\d{4}-\d{2}-\d{2})-(" + SLUG + r")\.md", path.name)
        try:
            if not filename:
                raise ValueError
            date.fromisoformat(filename[1])
        except ValueError:
            fail(path, "invalid decision date or topic filename")
        states = re.findall(r"^状态：([^\n]+)$", content, re.M)
        if states != [STATUS[lifecycle]]:
            fail(path, "note status does not match lifecycle")
        headings = {"先说结论", "问题", "考虑过的备选方案", "验证", "适用范围"}
        headings.update({"提案", "验收标准", "风险"} if lifecycle == "proposed" else {"决策", "后果"})
        actual_headings = set(re.findall(r"^## ([^\n]+)$", content, re.M))
        if not headings.issubset(actual_headings):
            fail(path, "note is missing required sections")
        if not re.match(r"# 工程笔记：\S", content):
            fail(path, "note needs a descriptive engineering-note title")

    return errors, {"files": len(contents), "skills": len(skills), "notes": note_count}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2], help="repository root")
    args = parser.parse_args()
    errors, counts = check(args.root)
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        print(f"Project rules: FAIL ({len(errors)} errors)", file=sys.stderr)
        return 1
    print(f"Project rules: OK ({counts['files']} files, {counts['skills']} skills, {counts['notes']} notes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
