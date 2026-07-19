"""Splits a documentation page into heading-scoped chunks.

One chunk per heading section, carrying the heading hierarchy, the section's
body text, and the section's anchored URL.
"""

from dataclasses import dataclass

from bs4 import BeautifulSoup, Tag

_HEADING_LEVELS = {"h1": 0, "h2": 1, "h3": 2, "h4": 3}
_CONTENT_TAGS = ("p", "li", "pre", "blockquote", "td", "dd")
_NOISE_TAGS = (
    "script",
    "style",
    "noscript",
    "template",
    "nav",
    "header",
    "footer",
    "aside",
)


@dataclass(frozen=True)
class Chunk:
    """One heading-scoped section of a page, ready to embed."""

    source_url: str
    heading_path: tuple[str, ...]
    content: str


def chunk_page(url: str, html: str, *, max_chars: int) -> list[Chunk]:
    """Split one page into structure-aware chunks of about ``max_chars``."""
    soup = BeautifulSoup(html, "html.parser")
    for noise in soup(_NOISE_TAGS):
        noise.decompose()
    root = soup.find("main") or soup.find("article") or soup.body or soup
    if not isinstance(root, Tag):
        return []

    title = (soup.title.get_text(strip=True) if soup.title else "")[:max_chars]
    headings: list[str] = [title] if title else []
    anchor = ""
    lines: list[str] = []
    size = 0
    chunks: list[Chunk] = []

    def flush() -> None:
        nonlocal size
        content = "\n".join(lines).strip()
        lines.clear()
        size = 0
        if content:
            section_url = f"{url}#{anchor}" if anchor else url
            chunks.append(Chunk(section_url, tuple(headings), content))

    for element in root.find_all([*_HEADING_LEVELS, *_CONTENT_TAGS]):
        if element.find_parent(_CONTENT_TAGS) is not None:
            continue
        text = element.get_text(" ", strip=True)
        if not text:
            continue
        level = _HEADING_LEVELS.get(element.name)
        if level is not None:
            flush()
            del headings[level:]
            # Bound the heading like content. Giant title must not push an
            # embedding input past the model's token limit.
            headings.append(text[:max_chars])
            heading_id = element.get("id")
            anchor = heading_id if isinstance(heading_id, str) else ""
        else:
            for start in range(0, len(text), max_chars):
                piece = text[start : start + max_chars]
                lines.append(piece)
                size += len(piece)
                if size >= max_chars:
                    flush()
    flush()
    return chunks
