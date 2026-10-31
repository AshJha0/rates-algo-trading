"""MkDocs hooks: publish the repository's root documents as site pages.

The root Markdown files (LEARN.md, COOKBOOK.md, ...) stay where GitHub shows
them. This hook registers each as a generated page so the site has one copy
of every document, and rewrites relative links so that they resolve on the
site (to the mapped page) or on GitHub (for files that are not pages).
"""

from __future__ import annotations

import json
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
    "compliance.md": "docs/compliance/RTS6_INVENTORY.md",
    "runbooks.md": "docs/runbooks/README.md",
    "edge-studies.md": "docs/research/EDGE_STUDIES.md",
}

# research notebooks (P5): page notebooks/<name>.md from research/notebooks/<name>.ipynb
# edge studies (r2): research/notebooks/edge_*.ipynb
NOTEBOOKS = ("hitrate", "markouts", "spread", "flow", "backtest_analysis") + tuple(
    sorted(p.stem for p in (REPO_ROOT / "research" / "notebooks").glob("edge_*.ipynb"))
)

_REVERSE: dict[str, str] = {source: page for page, source in PAGES.items()}
_SITE_PAGES = "docs/site/pages/"
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
        elif resolved.startswith(_SITE_PAGES):
            # a hand-written site page (for example the diagrams page); mapped pages sit at the root
            new_target = resolved[len(_SITE_PAGES) :]
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
    for name in NOTEBOOKS:
        files.append(File.generated(config, f"notebooks/{name}.md", content=_notebook_page(name)))
    return files


def _notebook_page(name: str) -> str:
    """The executed rendering when tools/run_notebooks.py has run, else the code cells only."""
    built = REPO_ROOT / "research" / "notebooks" / "_build" / f"{name}.md"
    if built.is_file():
        return built.read_text(encoding="utf-8")
    source = REPO_ROOT / "research" / "notebooks" / f"{name}.ipynb"
    nb = json.loads(source.read_text(encoding="utf-8"))
    parts = [
        "!!! note\n    Outputs appear when `python tools/run_notebooks.py` has run before the"
        " build; CI's docs job does that.\n"
    ]
    for cell in nb["cells"]:
        text = "".join(cell["source"])
        if cell["cell_type"] == "markdown":
            parts.append(text + "\n")
        else:
            parts.append(f"```python\n{text}\n```\n")
    return "\n".join(parts)
