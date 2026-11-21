"""Publish each docs page's Markdown source next to its HTML, for AI agents.

`/docs/nodes/` is also served as `/docs/nodes.md`, and every page's <head> points at it with
`<link rel="alternate" type="text/markdown">`. An agent can then fetch the one section it
needs instead of the whole `llms.txt`. The Markdown is the page's source as written (front
matter removed): the docs use no include syntax, so nothing is left unresolved.
"""

from pathlib import Path


def _markdown_path(url: str) -> str:
    # "" -> "index.md", "nodes/" -> "nodes.md", "codelabs/codelab-ar-compose/" -> "codelabs/codelab-ar-compose.md"
    return (url.rstrip("/") or "index") + ".md"


def on_post_page(output, page, config, **kwargs):
    if page.markdown is None or page.file.src_uri.endswith(".txt"):
        return output
    relative = _markdown_path(page.url)
    target = Path(config["site_dir"]) / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    title = page.title or ""
    body = page.markdown
    if title and not body.lstrip().startswith("# "):
        body = f"# {title}\n\n{body}"
    target.write_text(body, encoding="utf-8")
    link = f'<link rel="alternate" type="text/markdown" href="{config["site_url"].rstrip("/")}/{relative}">'
    return output.replace("</head>", f"  {link}\n</head>", 1)
