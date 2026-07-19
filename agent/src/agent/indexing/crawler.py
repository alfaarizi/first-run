"""Fetches a docs site page by page, never leaving the site's own host.

Egress guards mirror the webhook executor's: https only, every request
connects to an address vetted as publicly routable, every response has a
wall-clock deadline and a size cap, and redirects are enqueued rather than
followed. robots.txt is honored because the crawled site is public and not
necessarily all the tenant's own.
"""

import asyncio
import ipaddress
import logging
import socket
from collections import deque
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import AbstractAsyncContextManager
from dataclasses import dataclass, field
from urllib.parse import SplitResult, urldefrag, urljoin, urlsplit, urlunsplit

import httpx
from bs4 import BeautifulSoup, Tag
from protego import Protego

logger = logging.getLogger(__name__)

_USER_AGENT = "firstrun-crawler"
_ROBOTS_REDIRECT_HOPS = 5
_HTTPS_PORT = 443
_FRONTIER_SCALE = 10

Origin = tuple[str, int]


def _origin(split: SplitResult, allow_http: bool = False) -> Origin | None:
    # https only, with the default port folded in. A malformed authority,
    # such as an out-of-range port, reads as no origin. ``allow_http`` exists
    # for the local compose stack, whose demo docs have no TLS.
    schemes = ("https", "http") if allow_http else ("https",)
    if split.scheme not in schemes or not split.hostname:
        return None
    try:
        port = split.port
    except ValueError:
        return None
    return split.hostname, port or _HTTPS_PORT


def _join(base: str, url: str) -> str | None:
    """Resolve a link, reading a malformed URL as absent."""
    try:
        return urljoin(base, url)
    except ValueError:
        return None


class CrawlError(Exception):
    """The crawl cannot start or cannot finish completely."""


@dataclass(frozen=True)
class Page:
    """One fetched HTML page."""

    url: str
    html: str


@dataclass
class _Frontier:
    """Bounded, deduplicated queue of same-origin URLs still to fetch."""

    root_origin: Origin
    max_size: int
    allow_http: bool = False
    queue: deque[str] = field(default_factory=deque)
    seen: set[str] = field(default_factory=set)

    def add(self, url: str) -> None:
        if len(self.seen) >= self.max_size:
            return
        url = urldefrag(url).url
        try:
            on_site = _origin(urlsplit(url), self.allow_http) == self.root_origin
        except ValueError:
            return
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
    infos = await loop.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
    return [str(info[4][0]) for info in infos]


def _is_public(ip: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    # is_global alone admits multicast and some reserved space,
    # such as the NAT64 prefix that maps loopback.
    return ip.is_global and not (
        ip.is_multicast or ip.is_reserved or ip.is_link_local or ip.is_unspecified
    )


def _is_identity(response: httpx.Response) -> bool:
    # A compressed body ignores the identity request: unreadable as text and
    # a decompression risk to the size cap.
    encoding = response.headers.get("content-encoding", "").strip().lower()
    return encoding in ("", "identity")


def _decode(response: httpx.Response, body: bytes) -> str:
    try:
        return body.decode(response.charset_encoding or "utf-8", "replace")
    except LookupError:
        # The server declared a charset Python does not know.
        return body.decode("utf-8", "replace")


class Crawler:
    """Breadth-first crawler scoped to the root URL's host."""

    def __init__(
        self,
        *,
        max_pages: int,
        timeout_seconds: float,
        deadline_seconds: float,
        max_response_bytes: int,
        transport: httpx.AsyncBaseTransport | None = None,
        resolve: Callable[[str], Awaitable[list[str]]] = _resolve,
        allow_local: bool = False,
    ) -> None:
        self._max_pages = max_pages
        self._timeout = httpx.Timeout(timeout_seconds)
        self._deadline_seconds = deadline_seconds
        self._max_response_bytes = max_response_bytes
        self._transport = transport
        self._resolve = resolve
        # Lifts the public-address and https gates for the compose network.
        # Never set outside local development: it reopens SSRF.
        self._allow_local = allow_local

    async def crawl(self, root_url: str) -> AsyncIterator[Page]:
        """Yield pages reachable from ``root_url``, at most ``max_pages`` fetches."""
        root = urlsplit(root_url)
        root_origin = _origin(root, self._allow_local)
        if root_origin is None:
            raise CrawlError(f"not a public https URL: {root_url}")
        addresses = await self._resolve(root.hostname or "")
        if not self._allow_local:
            for address in addresses:
                if not _is_public(ipaddress.ip_address(address)):
                    raise CrawlError(
                        f"host resolves to a non-public address: {address}"
                    )

        async with httpx.AsyncClient(
            timeout=self._timeout,
            transport=self._transport,
            headers={
                "User-Agent": _USER_AGENT,
                # Identity keeps the size cap exact. A decoded stream would
                # let a small compressed body balloon past it.
                "Accept-Encoding": "identity",
            },
            # Proxy variables in the environment would route around the
            # pinned address.
            trust_env=False,
        ) as client:
            robots, pin = await self._connect(client, root_url, root, addresses)
            frontier = _Frontier(
                root_origin=root_origin,
                max_size=self._max_pages * _FRONTIER_SCALE,
                allow_http=self._allow_local,
            )
            frontier.add(root_url)
            attempts = 0
            while frontier.queue and attempts < self._max_pages:
                url = frontier.queue.popleft()
                if not robots.can_fetch(url, _USER_AGENT):
                    continue
                attempts += 1
                page = await self._fetch(client, url, frontier, pin)
                if page is not None:
                    yield page

    async def _connect(
        self,
        client: httpx.AsyncClient,
        root_url: str,
        root: SplitResult,
        addresses: list[str],
    ) -> tuple[Protego, _Pin]:
        """Pin the first vetted address that connects, keeping its robots.txt."""
        root_origin = _origin(root, self._allow_local)
        for address in addresses:
            pin = _Pin(
                netloc=root.netloc, hostname=root.hostname or "", address=address
            )
            try:
                return await self._fetch_robots(client, root_url, pin, root_origin), pin
            except (httpx.ConnectError, httpx.ConnectTimeout):
                logger.warning("address %s unreachable, trying the next", address)
        raise CrawlError(f"no address of {root.netloc} accepts connections")

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
        # Callers reject non-identity encodings first, so these are wire bytes
        # and the cap bounds allocation with no decompression to expand them.
        body = bytearray()
        async for part in response.aiter_bytes():
            body.extend(part)
            if len(body) > self._max_response_bytes:
                return None
        return bytes(body)

    async def _read_truncated(self, response: httpx.Response) -> bytes:
        body = bytearray()
        async for part in response.aiter_bytes():
            body.extend(part)
            if len(body) >= self._max_response_bytes:
                del body[self._max_response_bytes :]
                break
        return bytes(body)

    async def _fetch_robots(
        self,
        client: httpx.AsyncClient,
        root_url: str,
        pin: _Pin,
        root_origin: Origin | None,
    ) -> Protego:
        """Fetch robots.txt with RFC 9309 semantics.

        A 4xx answer allows the crawl and an oversized file parses truncated
        at the size cap. Anything that leaves the rules unknown disallows the
        crawl: a 5xx or unreachable host, a compressed body, or a redirect the
        pinned egress cannot follow, meaning off-site or past five same-site
        hops (RFC 9309 section 2.3.1.4).
        """
        rules = ""
        url = urljoin(root_url, "/robots.txt")
        try:
            async with asyncio.timeout(self._deadline_seconds):
                for _ in range(_ROBOTS_REDIRECT_HOPS + 1):
                    async with self._stream(client, url, pin) as response:
                        if response.is_redirect:
                            target = _join(url, response.headers.get("location", ""))
                            if (
                                target is None
                                or _origin(urlsplit(target), self._allow_local)
                                != root_origin
                            ):
                                raise CrawlError("robots.txt redirects off-site")
                            url = target
                            continue
                        if response.status_code >= 500:
                            raise CrawlError(
                                f"robots.txt answered {response.status_code}"
                            )
                        if response.status_code == 200:
                            if not _is_identity(response):
                                raise CrawlError("robots.txt is compressed")
                            rules = _decode(
                                response, await self._read_truncated(response)
                            )
                        break
                else:
                    raise CrawlError("robots.txt exceeded the redirect limit")
        except (httpx.ConnectError, httpx.ConnectTimeout):
            raise
        except (TimeoutError, httpx.HTTPError) as error:
            raise CrawlError(f"robots.txt unreachable: {error}") from error
        return Protego.parse(rules)

    async def _fetch(
        self, client: httpx.AsyncClient, url: str, frontier: _Frontier, pin: _Pin
    ) -> Page | None:
        try:
            async with asyncio.timeout(self._deadline_seconds):
                async with self._stream(client, url, pin) as response:
                    if response.is_redirect:
                        target = _join(url, response.headers.get("location", ""))
                        if target is not None:
                            frontier.add(target)
                        return None
                    if response.status_code >= 500 or response.status_code == 429:
                        raise CrawlError(f"{url} answered {response.status_code}")
                    content_type = response.headers.get("content-type", "")
                    if response.status_code != 200 or not content_type.startswith(
                        "text/html"
                    ):
                        return None
                    if not _is_identity(response):
                        logger.warning("skipping %s, unexpected encoding", url)
                        return None
                    body = await self._read_capped(response)
                    if body is None:
                        logger.warning("skipping %s, response over size cap", url)
                        return None
        except (TimeoutError, httpx.HTTPError) as error:
            # A transient failure fails the whole crawl.
            raise CrawlError(f"fetch of {url} failed: {error!r}") from error

        html = _decode(response, body)
        for link in _links(url, html):
            frontier.add(link)
        return Page(url=url, html=html)


def _links(page_url: str, html: str) -> list[str]:
    soup = BeautifulSoup(html, "html.parser")
    # A <base href> retargets all relative links. Resolve anchors against it.
    base = soup.find("base")
    base_href = base.get("href") if isinstance(base, Tag) else None
    base_url = page_url
    if isinstance(base_href, str):
        base_url = _join(page_url, base_href) or page_url
    links = []
    for element in soup.find_all("a"):
        href = element.get("href")
        if isinstance(href, str):
            link = _join(base_url, href)
            if link is not None:
                links.append(link)
    return links
