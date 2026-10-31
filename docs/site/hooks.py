"""MkDocs hooks: publish the repository's root documents as site pages.

The root Markdown files (LEARN.md, COOKBOOK.md, ...) stay where GitHub shows
them. This hook registers each as a generated page so the site has one copy
of every document, and rewrites relative links so that they resolve on the
site (to the mapped page) or on GitHub (for files that are not pages).
"""

from __future__ import annotations

import posixpath
import re
from pathlib import Path

from mkdocs.config.defaults import MkDocsConfig
from mkdocs.structure.files import File, Files

REPO_ROOT = Path(__file__).resolve().parents[2]

# site page -> repository file (relative to the repository root)
PAGES: dict[str, str] = {
    "learn.md": "LEARN.md",
    "cookbook.md": "COOKBOOK.md",
    "api-spec.md": "API_SPEC.md",
    "architecture.md": "docs/ARCHITECTURE.md",
    "benchmarks.md": "docs/BENCHMARKS.md",
    "decisions.md": "DECISIONS.md",
    "status.md": "STATUS.md",
    "ai-use-policy.md": "docs/AI_USE_POLICY.md",
    "changelog.md": "CHANGELOG.md",
    "publishing.md": "docs/GITHUB_PAGES.md",
}

_REVERSE: dict[str, str] = {source: page for page, source in PAGES.items()}
_LINK_RE = re.compile(r"(?<!!)\[([^\]]*)\]\(([^)\s]+)(\s+\"[^\"]*\")?\)")


def _rewrite_links(markdown: str, source_rel: str, repo_url: str) -> str:
    source_dir = posixpath.dirname(source_rel)

    def repl(match: re.Match[str]) -> str:
        text, target, title = match.group(1), match.group(2), match.group(3) or ""
        if target.startswith(("http://", "https://", "mailto:", "#", "/")):
            return match.group(0)
        path, _, anchor = target.partition("#")
        resolved = posixpath.normpath(posixpath.join(source_dir, path)) if path else source_rel
        if resolved in _REVERSE:
            new_target = _REVERSE[resolved]
        else:
            new_target = f"{repo_url.rstrip('/')}/blob/main/{resolved}"
        if anchor:
            new_target = f"{new_target}#{anchor}"
        return f"[{text}]({new_target}{title})"

    return _LINK_RE.sub(repl, markdown)


def on_files(files: Files, config: MkDocsConfig) -> Files:
    repo_url = config.repo_url or "https://github.com/AshJha0/rates-algo-trading"
    for page, source_rel in PAGES.items():
        source = REPO_ROOT / source_rel
        if not source.is_file():
            msg = f"hooks.py: mapped source {source_rel} for page {page} does not exist"
            raise FileNotFoundError(msg)
        content = _rewrite_links(source.read_text(encoding="utf-8"), source_rel, repo_url)
        files.append(File.generated(config, page, content=content))
    return files
