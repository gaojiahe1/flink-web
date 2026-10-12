"""Validate the project's lightweight knowledge links; optionally trace a cold retrieval."""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit

RULE = "每次执行新任务，必须优先通过项目知识索引恢复必要上下文，禁止无条件读取全部历史聊天或扫描全量代码。任务完成后，仅增量维护受影响的需求、模块、决策与状态文档。知识库是检索入口，不是无需验证的事实来源。"
LINK = re.compile(r"(?<!!)\[[^\]]+\]\(([^\s)]+)(?:\s+\"[^\"]*\")?\)")


def links(text: str):
    """Skip fenced examples so example URLs are not treated as live navigation."""
    fence = None
    visible = []
    for line in text.splitlines():
        marker = re.match(r"^\s{0,3}(`{3,}|~{3,})", line)
        if marker:
            if fence is None:
                fence = marker.group(1)[0]
            elif marker.group(1)[0] == fence:
                fence = None
            continue
        if fence is None:
            visible.append(line)
    return LINK.findall("\n".join(visible))


def resolve(root: Path, source: Path, url: str):
    """Return local path and fragment, or None for an external link."""
    parsed = urlsplit(url)
    if parsed.scheme or parsed.netloc:
        return None
    path = (source.parent / unquote(parsed.path)).resolve() if parsed.path else source
    if not path.is_relative_to(root):
        raise ValueError("link leaves project: " + url)
    return path, unquote(parsed.fragment)


def anchors(text: str):
    """Compute GitHub-style anchors for the ATX headings used by these documents."""
    seen = {}
    result = set()
    fence = None
    for line in text.splitlines():
        marker = re.match(r"^\s{0,3}(`{3,}|~{3,})", line)
        if marker:
            if fence is None:
                fence = marker.group(1)[0]
            elif marker.group(1)[0] == fence:
                fence = None
            continue
        if fence is not None:
            continue
        match = re.match(r"^#{1,6}\s+(.+?)\s*#*\s*$", line)
        if not match:
            continue
        title = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", match.group(1))
        slug = re.sub(r"[^\w\- ]", "", title.lower()).replace(" ", "-")
        number = seen.get(slug, 0)
        seen[slug] = number + 1
        result.add(slug + ("-" + str(number) if number else ""))
    return result


def documents(root: Path):
    """Only inspect knowledge/navigation documents, never the full source tree."""
    paths = [root / p for p in ("AGENTS.md", "README.md", "docs/features.md", "docs/deployment.md")]
    paths += sorted((root / "docs/agent").rglob("*.md"))
    return {p: p.read_text(encoding="utf-8") for p in paths if p.is_file()}


def validate(root: Path, changed: list[str] | None):
    docs = documents(root)
    errors = []
    for required in ("AGENTS.md", "docs/agent/INDEX.md", "docs/agent/current-state.md"):
        if root / required not in docs:
            errors.append("missing entry: " + required)
    if RULE not in docs.get(root / "AGENTS.md", ""):
        errors.append("AGENTS.md is missing the exact long-term rule")
    selected = set(docs)
    if changed:
        targets = {(root / p).resolve() for p in changed}
        if any(not p.is_relative_to(root) for p in targets):
            return ["changed path leaves project"], 0, 0
        selected = targets & set(docs)
        for source, text in docs.items():
            for url in links(text):
                try:
                    local = resolve(root, source, url)
                except ValueError:
                    continue
                if local and local[0] in targets:
                    selected.add(source)
    checked = 0
    for source in sorted(selected):
        for url in links(docs[source]):
            try:
                local = resolve(root, source, url)
                if local is None:
                    continue
                checked += 1
                target, fragment = local
                if not target.exists():
                    errors.append(f"{source.relative_to(root)}: missing {url}")
                elif fragment and target.suffix.lower() == ".md":
                    text = docs.get(target)
                    if text is None:
                        text = target.read_text(encoding="utf-8")
                    if fragment not in anchors(text):
                        errors.append(f"{source.relative_to(root)}: missing anchor {url}")
            except (ValueError, OSError) as error:
                errors.append(f"{source.relative_to(root)}: {error}")
    return errors, len(selected), checked


def demo(root: Path, keyword: str):
    """Recover from the entry/index and one matched row, without chat history."""
    reads = {}

    def read(path: Path):
        data = path.read_bytes()
        reads[path.relative_to(root).as_posix()] = len(data)
        return data.decode("utf-8")

    read(root / "AGENTS.md")
    index = root / "docs/agent/INDEX.md"
    text = read(index)
    row = next((line for line in text.splitlines() if line.startswith("| ")
                and keyword.casefold() in line.split("|")[1].casefold()), None)
    if row is None:
        raise ValueError("no keyword route: " + keyword)
    module = None
    for url in links(row):
        target = resolve(root, index, url)
        if target:
            value = read(target[0])
            if target[0].parent.name == "modules":
                module = (target[0], value)
    if module is None:
        raise ValueError("route has no module: " + keyword)
    source, text = module
    code = []
    for url in links(text):
        target = resolve(root, source, url)
        if target and target[0].suffix in (".java", ".vue", ".ts", ".py", ".sh"):
            code.append(target[0])
    if not code:
        raise ValueError("module has no code links")
    read(code[0])
    tests = [p for p in code if "/test" in p.as_posix()]
    if tests:
        read(tests[0])
    return {"keyword": keyword, "matched_index_row": row,
            "read_files": [{"path": p, "bytes": size} for p, size in reads.items()],
            "file_count": len(reads), "total_bytes": sum(reads.values()),
            "scope": "entry, one index row, associated requirement/module/decision, one source and one test",
            "limits": "navigation trace only; business correctness still requires code/test review"}


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--changed", nargs="+", help="Changed doc or old/new code paths; include direct referring docs")
    parser.add_argument("--demo", help="Trace one keyword, e.g. savepoint")
    args = parser.parse_args()
    root = args.root.resolve()
    if args.demo:
        try:
            print(json.dumps(demo(root, args.demo), ensure_ascii=False, indent=2))
        except (OSError, ValueError) as error:
            print("FAIL: " + str(error))
            return 1
        return 0
    errors, count, references = validate(root, args.changed)
    if errors:
        print("\n".join(errors))
        return 1
    print(f"PASS: {count} documents; {references} local links/anchors; exact AGENTS rule")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
