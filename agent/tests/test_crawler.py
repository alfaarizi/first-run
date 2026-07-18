"""The crawler stays on the root host and honors its safety caps."""

import asyncio

import httpx
import pytest

from agent.indexing.crawler import Crawler, CrawlError

_ROOT = "https://docs.example.com"
_ADDRESS = "93.184.216.34"


async def _public(host: str) -> list[str]:
    return [_ADDRESS]


async def _loopback(host: str) -> list[str]:
    return ["127.0.0.1"]


async def _multicast(host: str) -> list[str]:
    return ["224.0.0.1"]


def _html(body: str) -> httpx.Response:
    return httpx.Response(200, headers={"content-type": "text/html"}, text=body)


def _site(request: httpx.Request) -> httpx.Response:
    routes = {
        "/": _html(
            """
            <a href="/guide">Guide</a>
            <a href="/guide">Guide again</a>
            <a href="/old">Old</a>
            <a href="/private/internal">Internal</a>
            <a href="/theme.css">Theme</a>
            <a href="https://evil.example.net/">Offsite</a>
            <a href="http://docs.example.com/downgrade">Downgrade</a>
            """
        ),
        "/guide": _html("<p>Guide</p>"),
        "/old": httpx.Response(301, headers={"location": "/moved"}),
        "/moved": _html("<p>Moved</p>"),
        "/private/internal": _html("<p>Secret</p>"),
        "/theme.css": httpx.Response(
            200, headers={"content-type": "text/css"}, text="body{}"
        ),
        "/robots.txt": httpx.Response(
            200,
            headers={"content-type": "text/plain"},
            text="User-agent: *\nDisallow: /private/",
        ),
    }
    return routes.get(request.url.path, httpx.Response(404))


def _crawler(
    transport: httpx.MockTransport | None = None,
    max_pages: int = 50,
    max_response_bytes: int = 10_000,
) -> Crawler:
    return Crawler(
        max_pages=max_pages,
        timeout_seconds=1.0,
        deadline_seconds=5.0,
        max_response_bytes=max_response_bytes,
        transport=transport or httpx.MockTransport(_site),
        resolve=_public,
    )


async def test_crawls_same_host_pages_only() -> None:
    urls = [page.url async for page in _crawler().crawl(f"{_ROOT}/")]

    assert f"{_ROOT}/guide" in urls
    assert all(url.startswith(_ROOT) for url in urls)


async def test_deduplicates_and_skips_non_html() -> None:
    urls = [page.url async for page in _crawler().crawl(f"{_ROOT}/")]

    assert urls.count(f"{_ROOT}/guide") == 1
    assert f"{_ROOT}/theme.css" not in urls


async def test_redirects_are_enqueued_not_followed() -> None:
    urls = [page.url async for page in _crawler().crawl(f"{_ROOT}/")]

    assert f"{_ROOT}/moved" in urls


async def test_robots_disallow_is_honored() -> None:
    urls = [page.url async for page in _crawler().crawl(f"{_ROOT}/")]

    assert f"{_ROOT}/private/internal" not in urls


async def test_requests_connect_to_the_vetted_address() -> None:
    wire = []

    def record(request: httpx.Request) -> httpx.Response:
        wire.append((request.url.host, request.headers["host"]))
        return _site(request)

    crawler = _crawler(transport=httpx.MockTransport(record))
    [page.url async for page in crawler.crawl(f"{_ROOT}/")]

    assert wire
    assert all(host == _ADDRESS for host, _ in wire)
    assert all(header == "docs.example.com" for _, header in wire)


async def test_page_cap_bounds_the_crawl() -> None:
    urls = [page.url async for page in _crawler(max_pages=2).crawl(f"{_ROOT}/")]

    assert len(urls) == 2


async def test_page_cap_bounds_failed_fetches_too() -> None:
    requests = []

    def fanout(request: httpx.Request) -> httpx.Response:
        if request.url.path != "/robots.txt":
            requests.append(request.url.path)
        if request.url.path == "/":
            links = "".join(f'<a href="/missing-{i}">x</a>' for i in range(50))
            return _html(links)
        return httpx.Response(404)

    crawler = _crawler(transport=httpx.MockTransport(fanout), max_pages=5)
    urls = [page.url async for page in crawler.crawl(f"{_ROOT}/")]

    assert urls == [f"{_ROOT}/"]
    assert len(requests) == 5


async def test_oversized_pages_are_skipped() -> None:
    crawler = _crawler(max_response_bytes=10)

    assert [page.url async for page in crawler.crawl(f"{_ROOT}/")] == []


async def test_drip_fed_pages_hit_the_wall_clock_deadline() -> None:
    async def slow_site(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/robots.txt":
            return httpx.Response(404)
        await asyncio.sleep(0.2)
        return _html("<p>Slow</p>")

    crawler = Crawler(
        max_pages=10,
        timeout_seconds=1.0,
        deadline_seconds=0.05,
        max_response_bytes=10_000,
        transport=httpx.MockTransport(slow_site),
        resolve=_public,
    )

    assert [page.url async for page in crawler.crawl(f"{_ROOT}/")] == []


async def test_connect_timeout_pin_falls_back_to_the_next_address() -> None:
    async def two_addresses(host: str) -> list[str]:
        return [_ADDRESS, "93.184.216.35"]

    def first_hangs(request: httpx.Request) -> httpx.Response:
        if request.url.host == _ADDRESS:
            raise httpx.ConnectTimeout("timed out")
        return _site(request)

    crawler = Crawler(
        max_pages=10,
        timeout_seconds=1.0,
        deadline_seconds=5.0,
        max_response_bytes=10_000,
        transport=httpx.MockTransport(first_hangs),
        resolve=two_addresses,
    )
    urls = [page.url async for page in crawler.crawl(f"{_ROOT}/")]

    assert f"{_ROOT}/" in urls


async def test_oversized_robots_is_truncated_and_still_honored() -> None:
    def huge_robots(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/robots.txt":
            return httpx.Response(
                200,
                headers={"content-type": "text/plain"},
                text="User-agent: *\nDisallow: /private/\n" + "#" * 20_000,
            )
        return _site(request)

    crawler = _crawler(transport=httpx.MockTransport(huge_robots))
    urls = [page.url async for page in crawler.crawl(f"{_ROOT}/")]

    assert f"{_ROOT}/" in urls
    assert f"{_ROOT}/private/internal" not in urls


async def test_redirected_robots_is_followed_and_honored() -> None:
    def moved_robots(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/robots.txt":
            return httpx.Response(302, headers={"location": "/rules.txt"})
        if request.url.path == "/rules.txt":
            return httpx.Response(
                200,
                headers={"content-type": "text/plain"},
                text="User-agent: *\nDisallow: /private/",
            )
        return _site(request)

    crawler = _crawler(transport=httpx.MockTransport(moved_robots))
    urls = [page.url async for page in crawler.crawl(f"{_ROOT}/")]

    assert f"{_ROOT}/" in urls
    assert f"{_ROOT}/private/internal" not in urls


async def test_robots_redirect_loop_reads_as_unavailable() -> None:
    def looping_robots(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/robots.txt":
            return httpx.Response(302, headers={"location": "/robots.txt"})
        return _site(request)

    crawler = _crawler(transport=httpx.MockTransport(looping_robots))
    urls = [page.url async for page in crawler.crawl(f"{_ROOT}/")]

    assert f"{_ROOT}/" in urls


async def test_offsite_robots_redirect_reads_as_unavailable() -> None:
    def offsite_robots(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/robots.txt":
            return httpx.Response(
                302, headers={"location": "https://cdn.example.net/robots.txt"}
            )
        return _site(request)

    crawler = _crawler(transport=httpx.MockTransport(offsite_robots))
    urls = [page.url async for page in crawler.crawl(f"{_ROOT}/")]

    assert f"{_ROOT}/" in urls


async def test_robots_wildcard_disallow_is_honored() -> None:
    def wildcard_robots(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/robots.txt":
            return httpx.Response(
                200,
                headers={"content-type": "text/plain"},
                text="User-agent: *\nDisallow: /*.tmp$",
            )
        if request.url.path == "/":
            return _html('<a href="/draft.tmp">Draft</a><a href="/guide">Guide</a>')
        return _site(request)

    crawler = _crawler(transport=httpx.MockTransport(wildcard_robots))
    urls = [page.url async for page in crawler.crawl(f"{_ROOT}/")]

    assert f"{_ROOT}/guide" in urls
    assert f"{_ROOT}/draft.tmp" not in urls


async def test_robots_denied_by_4xx_allows_the_crawl() -> None:
    def forbidden_robots(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/robots.txt":
            return httpx.Response(403)
        return _site(request)

    crawler = _crawler(transport=httpx.MockTransport(forbidden_robots))
    urls = [page.url async for page in crawler.crawl(f"{_ROOT}/")]

    assert f"{_ROOT}/" in urls


async def test_robots_5xx_disallows_the_whole_crawl() -> None:
    def broken_robots(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/robots.txt":
            return httpx.Response(503)
        return _site(request)

    crawler = _crawler(transport=httpx.MockTransport(broken_robots))
    with pytest.raises(CrawlError):
        async for _ in crawler.crawl(f"{_ROOT}/"):
            pass


async def test_non_https_root_is_refused() -> None:
    with pytest.raises(CrawlError):
        async for _ in _crawler().crawl("http://docs.example.com/"):
            pass


async def test_private_address_root_is_refused() -> None:
    crawler = Crawler(
        max_pages=10,
        timeout_seconds=1.0,
        deadline_seconds=5.0,
        max_response_bytes=10_000,
        transport=httpx.MockTransport(_site),
        resolve=_loopback,
    )

    with pytest.raises(CrawlError):
        async for _ in crawler.crawl(f"{_ROOT}/"):
            pass


async def test_multicast_address_root_is_refused() -> None:
    crawler = Crawler(
        max_pages=10,
        timeout_seconds=1.0,
        deadline_seconds=5.0,
        max_response_bytes=10_000,
        transport=httpx.MockTransport(_site),
        resolve=_multicast,
    )

    with pytest.raises(CrawlError):
        async for _ in crawler.crawl(f"{_ROOT}/"):
            pass


async def test_nat64_reserved_address_root_is_refused() -> None:
    async def nat64(host: str) -> list[str]:
        return ["64:ff9b::7f00:1"]

    crawler = Crawler(
        max_pages=10,
        timeout_seconds=1.0,
        deadline_seconds=5.0,
        max_response_bytes=10_000,
        transport=httpx.MockTransport(_site),
        resolve=nat64,
    )

    with pytest.raises(CrawlError):
        async for _ in crawler.crawl(f"{_ROOT}/"):
            pass


async def test_unreachable_pin_falls_back_to_the_next_address() -> None:
    async def two_addresses(host: str) -> list[str]:
        return [_ADDRESS, "93.184.216.35"]

    def first_dead(request: httpx.Request) -> httpx.Response:
        if request.url.host == _ADDRESS:
            raise httpx.ConnectError("refused")
        return _site(request)

    crawler = Crawler(
        max_pages=10,
        timeout_seconds=1.0,
        deadline_seconds=5.0,
        max_response_bytes=10_000,
        transport=httpx.MockTransport(first_dead),
        resolve=two_addresses,
    )
    urls = [page.url async for page in crawler.crawl(f"{_ROOT}/")]

    assert f"{_ROOT}/" in urls


async def test_no_reachable_address_fails_the_crawl() -> None:
    def dead(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused")

    crawler = _crawler(transport=httpx.MockTransport(dead))

    with pytest.raises(CrawlError):
        async for _ in crawler.crawl(f"{_ROOT}/"):
            pass
