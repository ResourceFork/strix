#!/usr/bin/env python3
"""Verify every relative markdown link and #anchor across the repo's docs.

The doc set is heavily cross-linked, so a renamed heading silently breaks
navigation in places you weren't editing. Standard library only, no install:

    python3 scripts/check-doc-links.py

Checks relative links only. External http(s) links are listed but not fetched.
"""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
LINK = re.compile(r"\[[^\]]*\]\(([^)]+)\)")
ATX = re.compile(r"^(#{1,6})\s+(.*?)\s*#*$", re.MULTILINE)
# GitHub also generates anchors from <a id="..."> / <a name="...">
EXPLICIT = re.compile(r"<a\s+(?:id|name)=[\"']([^\"']+)[\"']")


def slug(text: str) -> str:
    """GitHub's heading-to-anchor transform, close enough for our headings."""
    text = re.sub(r"`([^`]*)`", r"\1", text)          # strip inline code
    text = re.sub(r"\*\*?([^*]*)\*\*?", r"\1", text)  # strip emphasis
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)  # links -> label
    text = text.strip().lower()
    text = re.sub(r"[^\w\s-]", "", text)              # drop punctuation/emoji
    # One hyphen per whitespace CHARACTER, not per run. Dropping an em dash from
    # "test — measure" leaves two spaces behind, and GitHub's anchor keeps both:
    # "test--measure". Collapsing runs here reports false failures.
    return re.sub(r"\s", "-", text)


def anchors_of(path: pathlib.Path) -> set[str]:
    body = path.read_text(encoding="utf-8")
    found = {slug(m.group(2)) for m in ATX.finditer(body)}
    found |= set(EXPLICIT.findall(body))
    return found


def main() -> int:
    docs = sorted(ROOT.rglob("*.md"))
    docs = [d for d in docs if ".git" not in d.parts and "build" not in d.parts]
    cache: dict[pathlib.Path, set[str]] = {}
    total = external = 0
    problems: list[str] = []

    for doc in docs:
        for target in LINK.findall(doc.read_text(encoding="utf-8")):
            total += 1
            target = target.split(" ")[0]  # drop optional "title"
            if target.startswith(("http://", "https://", "mailto:")):
                external += 1
                continue

            rel, _, frag = target.partition("#")
            dest = doc.parent / rel if rel else doc
            here = doc.relative_to(ROOT)

            if not dest.exists():
                problems.append(f"{here}: missing file -> {target}")
                continue
            if not frag or dest.suffix != ".md":
                continue
            if dest not in cache:
                cache[dest] = anchors_of(dest)
            if frag.lower() not in cache[dest]:
                problems.append(f"{here}: no anchor '#{frag}' in {rel or here.name}")

    print(f"{len(docs)} files, {total} links ({external} external, skipped)")
    if problems:
        print(f"\n{len(problems)} problem(s):")
        for p in problems:
            print(f"  {p}")
        return 1
    print("all relative links and anchors resolve")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
