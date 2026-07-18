"""Fetches a docs site page by page, never leaving the site's own host.

Egress guards mirror the webhook executor's: https only, every request
connects to an address vetted as publicly routable, every response has a
timeout and a size cap, and redirects are enqueued rather than followed.
robots.txt is honored because the crawled site is public and not necessarily
all the tenant's own.
"""

import asyncio
import ipaddress
import logging
import urllib.robotparser
from collections import deque
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import AbstractAsyncContextManager
from dataclasses import dataclass, field
from urllib.parse import urldefrag, urljoin, urlsplit, urlunsplit

import httpx
from bs4 import BeautifulSoup

logger = logging.getLogger(__name__)

_USER_AGENT = "firstrun-crawler"


class CrawlError(Exception):
    """The root URL is unusable, so the crawl cannot start."""


@dataclass(frozen=True)
class Page:
    """One fetched HTML page."""

    url: str
    html: str


@dataclass
class _Frontier:
    """Deduplicated queue of same-site URLs still to fetch."""

    root_netloc: str
    queue: deque[str] = field(default_factory=deque)
    seen: set[str] = field(default_factory=set)

    def add(self, url: str) -> None:
        url = urldefrag(url).url
        split = urlsplit(url)
        on_site = split.scheme == "https" and split.netloc == self.root_netloc
        if on_site and url not in self.seen:
            self.seen.add(url)
            self.queue.append(url)


@dataclass(frozen=True)
class _Pin:
    """The vetted address every request connects to, with the host it serves."""

    netloc: str
    hostname: str
    address: str

    def wire_url(self, url: str) -> str:
        """Swap the URL's host for the pinned address, keeping any port."""
        split = urlsplit(url)
        host = f"[{self.address}]" if ":" in self.address else self.address
        netloc = f"{host}:{split.port}" if split.port else host
        return urlunsplit((split.scheme, netloc, split.path, split.query, ""))


async def _resolve(host: str) -> list[str]:
    loop = asyncio.get_running_loop()
    infos = await loop.getaddrinfo(host, 443)
    return [str(info[4][0]) for info in infos]


class Crawler:
    """Breadth-first crawler scoped to the root URL's host."""

    def __init__(
        self,
        *,
        max_pages: int,
        timeout_seconds: float,
        max_response_bytes: int,
        transport: httpx.AsyncBaseTransport | None = None,
        resolve: Callable[[str], Awaitable[list[str]]] = _resolve,
    ) -> None:
        self._max_pages = max_pages
        self._timeout = httpx.Timeout(timeout_seconds)
        self._max_response_bytes = max_response_bytes
        self._transport = transport
        self._resolve = resolve

    async def crawl(self, root_url: str) -> AsyncIterator[Page]:
        """Yield pages reachable from ``root_url``, fetching at most ``max_pages`` URLs."""
        root = urlsplit(root_url)
        if root.scheme != "https" or not root.netloc:
            raise CrawlError(f"not a public https URL: {root_url}")
        addresses = await self._resolve(root.hostname or "")
        for address in addresses:
            if not ipaddress.ip_address(address).is_global:
                raise CrawlError(f"host resolves to a non-public address: {address}")
        # Every request connects to the first vetted address, so a DNS answer
        # that changes mid-crawl cannot steer the crawl into a private network.
        pin = _Pin(
            netloc=root.netloc, hostname=root.hostname or "", address=addresses[0]
        )

        async with httpx.AsyncClient(
            timeout=self._timeout,
            transport=self._transport,
            headers={"User-Agent": _USER_AGENT},
        ) as client:
            robots = await self._fetch_robots(client, root_url, pin)
            frontier = _Frontier(root_netloc=root.netloc)
            frontier.add(root_url)
            attempts = 0
            while frontier.queue and attempts < self._max_pages:
                url = frontier.queue.popleft()
                if not robots.can_fetch(_USER_AGENT, url):
                    continue
                attempts += 1
                page = await self._fetch(client, url, frontier, pin)
                if page is not None:
                    yield page

    def _stream(
        self, client: httpx.AsyncClient, url: str, pin: _Pin
    ) -> AbstractAsyncContextManager[httpx.Response]:
        return client.stream(
            "GET",
            pin.wire_url(url),
            headers={"Host": pin.netloc},
            extensions={"sni_hostname": pin.hostname},
        )

    async def _read_capped(self, response: httpx.Response) -> bytes | None:
        body = bytearray()
        async for part in response.aiter_bytes():
            body.extend(part)
            if len(body) > self._max_response_bytes:
                return None
        return bytes(body)

    async def _fetch_robots(
        self, client: httpx.AsyncClient, root_url: str, pin: _Pin
    ) -> urllib.robotparser.RobotFileParser:
        robots = urllib.robotparser.RobotFileParser()
        lines: list[str] = []
        try:
            async with self._stream(
                client, urljoin(root_url, "/robots.txt"), pin
            ) as response:
                if response.status_code == 200:
                    body = await self._read_capped(response)
                    if body is None:
                        logger.warning("robots.txt over size cap, crawling without it")
                    else:
                        lines = body.decode(
                            response.charset_encoding or "utf-8", "replace"
                        ).splitlines()
        except httpx.HTTPError:
            logger.warning("robots.txt fetch failed, crawling without it")
        robots.parse(lines)
        return robots

    async def _fetch(
        self, client: httpx.AsyncClient, url: str, frontier: _Frontier, pin: _Pin
    ) -> Page | None:
        try:
            async with self._stream(client, url, pin) as response:
                if response.is_redirect:
                    frontier.add(urljoin(url, response.headers.get("location", "")))
                    return None
                content_type = response.headers.get("content-type", "")
                if response.status_code != 200 or not content_type.startswith(
                    "text/html"
                ):
                    return None
                body = await self._read_capped(response)
                if body is None:
                    logger.warning("skipping %s, response over size cap", url)
                    return None
        except httpx.HTTPError:
            logger.warning("skipping %s, request failed", url)
            return None

        html = body.decode(response.charset_encoding or "utf-8", "replace")
        for link in _links(url, html):
            frontier.add(link)
        return Page(url=url, html=html)


def _links(page_url: str, html: str) -> list[str]:
    soup = BeautifulSoup(html, "html.parser")
    links = []
    for element in soup.find_all("a"):
        href = element.get("href")
        if isinstance(href, str):
            links.append(urljoin(page_url, href))
    return links
