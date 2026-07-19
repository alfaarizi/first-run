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


class _Sections:
    """Accumulates a page's text into chunks, one open section at a time."""

    def __init__(self, url: str, title: str, max_chars: int) -> None:
        """Start at the page root, with the title as the top heading."""
        self._url = url
        self._max_chars = max_chars
        self._headings: list[str] = [title] if title else []
        self._anchor = ""
        self._lines: list[str] = []
        self._size = 0
        self.chunks: list[Chunk] = []

    def open_section(self, level: int, heading: str, anchor: str) -> None:
        """Close the open section and descend the heading path to this one."""
        self.flush()
        del self._headings[level:]
        # Bound the heading like content. A giant heading must not push an
        # embedding input past the model's token limit.
        self._headings.append(heading[: self._max_chars])
        self._anchor = anchor

    def add_text(self, text: str) -> None:
        """Buffer body text, emitting a chunk whenever one fills."""
        for start in range(0, len(text), self._max_chars):
            piece = text[start : start + self._max_chars]
            self._lines.append(piece)
            self._size += len(piece)
            if self._size >= self._max_chars:
                self.flush()

    def flush(self) -> None:
        """Emit the buffered text as one chunk, when any remains."""
        content = "\n".join(self._lines).strip()
        self._lines.clear()
        self._size = 0
        if content:
            section_url = f"{self._url}#{self._anchor}" if self._anchor else self._url
            self.chunks.append(Chunk(section_url, tuple(self._headings), content))


def chunk_page(url: str, html: str, *, max_chars: int) -> list[Chunk]:
    """Split one page into structure-aware chunks of about ``max_chars``."""
    soup = BeautifulSoup(html, "html.parser")
    for noise in soup(_NOISE_TAGS):
        noise.decompose()
    root = soup.find("main") or soup.find("article") or soup.body or soup
    if not isinstance(root, Tag):
        return []

    title = (soup.title.get_text(strip=True) if soup.title else "")[:max_chars]
    sections = _Sections(url, title, max_chars)
    for element in root.find_all([*_HEADING_LEVELS, *_CONTENT_TAGS]):
        # A nested content tag, such as a <p> inside an <li>, would repeat
        # its text; only the outermost carrier counts.
        if element.find_parent(_CONTENT_TAGS) is not None:
            continue
        text = element.get_text(" ", strip=True)
        if not text:
            continue
        if element.name in _HEADING_LEVELS:
            sections.open_section(_HEADING_LEVELS[element.name], text, _anchor(element))
        else:
            sections.add_text(text)
    sections.flush()
    return sections.chunks


def _anchor(heading: Tag) -> str:
    """Read the heading's fragment id, absent as empty."""
    heading_id = heading.get("id")
    return heading_id if isinstance(heading_id, str) else ""
