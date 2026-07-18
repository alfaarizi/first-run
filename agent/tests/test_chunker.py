"""Chunking splits a page along its heading hierarchy."""

from pathlib import Path

from agent.indexing.chunker import chunk_page

_URL = "https://docs.tasklet.dev/guide"
_HTML = (Path(__file__).parent / "fixtures" / "docs_page.html").read_text()


def test_one_chunk_per_heading_section() -> None:
    chunks = chunk_page(_URL, _HTML, max_chars=2_000)

    assert [chunk.heading_path for chunk in chunks] == [
        ("Tasklet",),
        ("Tasklet", "Getting started"),
        ("Tasklet", "Getting started", "API keys"),
        ("Tasklet", "Billing"),
    ]


def test_chunks_carry_anchored_source_urls() -> None:
    chunks = chunk_page(_URL, _HTML, max_chars=2_000)

    assert [chunk.source_url for chunk in chunks] == [
        f"{_URL}#tasklet",
        f"{_URL}#getting-started",
        f"{_URL}#api-keys",
        f"{_URL}#billing",
    ]


def test_section_content_includes_lists_and_code() -> None:
    chunks = chunk_page(_URL, _HTML, max_chars=2_000)

    getting_started = chunks[1].content
    assert "Create a project from the dashboard." in getting_started
    assert "Invite a teammate." in getting_started
    assert "curl https://api.tasklet.dev/v1/tasks" in chunks[2].content


def test_navigation_scripts_and_footer_are_dropped() -> None:
    text = "\n".join(
        chunk.content for chunk in chunk_page(_URL, _HTML, max_chars=2_000)
    )

    assert "Pricing" not in text
    assert "Copyright" not in text
    assert "analytics" not in text
    assert "margin" not in text


def test_long_sections_split_under_the_cap_with_the_same_heading() -> None:
    paragraphs = "".join(f"<p>Paragraph number {i} is here.</p>" for i in range(40))
    html = f"<title>T</title><body><h2 id='a'>Long</h2>{paragraphs}</body>"

    chunks = chunk_page(_URL, html, max_chars=200)

    assert len(chunks) > 1
    assert {chunk.heading_path for chunk in chunks} == {("T", "Long")}
    assert all(len(chunk.content) <= 200 + 40 for chunk in chunks)


def test_empty_page_yields_no_chunks() -> None:
    assert chunk_page(_URL, "<html><body></body></html>", max_chars=200) == []
