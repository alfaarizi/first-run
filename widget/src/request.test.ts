import { afterEach, expect, test, vi } from "vitest";

import { post, sign } from "./request";
import type { Config } from "./types";

const config: Config = {
  key: "pk",
  secret: "sk",
  host: "https://gw.example",
  allowlist: new Set(),
};

afterEach(() => {
  vi.unstubAllGlobals();
});

test("signs timestamp.body with HMAC-SHA256 as lowercase hex", async () => {
  // the golden vector, HMAC-SHA256 of the fixed timestamp.body under "sk"
  expect(await sign("sk", "2026-07-16T00:00:00.000Z", '{"events":[]}')).toBe(
    "86af56bb3a41d1557bcd3655aff7bcecf6d47c0fd696d1cae8cfdccdd8f4d14a",
  );
});

test("posts with keepalive and the key, time1stamp, and signature headers", async () => {
  const fetchMock = vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status: 202 }));
  vi.stubGlobal("fetch", fetchMock);

  const result = await post(config, "/v1/e", "{}");

  expect(result).toEqual({ ok: true, retryable: false, refused: false });
  const [url, init] = fetchMock.mock.calls[0]!;
  expect(url).toBe("https://gw.example/v1/e");
  expect(init?.keepalive).toBe(true);

  const headers = init?.headers as Record<string, string>;
  expect(headers["Content-Type"]).toBe("application/json");
  expect(headers["X-FirstRun-Key"]).toBe("pk");
  expect(headers["X-FirstRun-Signature"]).toBe(
    await sign(config.secret, headers["X-FirstRun-Timestamp"]!, "{}"),
  );
});

test("marks 429 and 5xx retryable and other 4xx permanent", async () => {
  for (const [status, retryable] of [
    [429, true],
    [503, true],
    [400, false],
  ] as const) {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status })),
    );
    expect(await post(config, "/v1/e", "{}")).toMatchObject({ ok: false, retryable });
  }
});

test("reports a refusal only where the server answered and did not take it", async () => {
  // A refused message is never forwarded, so the widget can settle its question
  // at once. A 5xx or a dropped connection leaves it unknown, and settling one
  // of those would discard an answer already on its way.
  for (const [status, refused] of [
    [429, true],
    [400, true],
    [503, false],
  ] as const) {
    vi.stubGlobal(
      "fetch",
      vi.fn<typeof fetch>().mockResolvedValue(new Response(null, { status })),
    );
    expect(await post(config, "/v1/e", "{}")).toMatchObject({ refused });
  }
});

test("marks a network failure retryable, and never a refusal", async () => {
  vi.stubGlobal("fetch", vi.fn<typeof fetch>().mockRejectedValue(new TypeError("failed")));

  expect(await post(config, "/v1/e", "{}")).toEqual({
    ok: false,
    retryable: true,
    refused: false,
  });
});
