#!/usr/bin/env -S deno run --allow-net --allow-env
// Same-origin CORS proxy for the wasmJs build of TagHistory.
//
// Apple's GSA / iCloud and the public anisette server don't ship CORS
// headers, so the browser refuses cross-origin requests. This proxy
// rewrites `/<service>/...` to the upstream host, returns
// `Access-Control-Allow-Origin: *`, and forwards the body verbatim.
//
// Run with:
//   deno run --allow-net scripts/web-proxy.ts
// Default port 8770. Override with PROXY_PORT=NNNN.
//
// Wire into WasmAppHost like:
//   WasmAppHost(..., anisetteUrl = "http://localhost:8770/anisette",
//                    gsaEndpoint  = "http://localhost:8770/gsa",
//                    mobileMeEndpoint = "http://localhost:8770/mobileme")
//
// The proxy is INSECURE — it accepts requests from any origin and
// forwards them with the upstream cookies. Use only for local dev or
// behind a trusted edge.

const PORT = Number(Deno.env.get("PROXY_PORT") ?? 8770);

const UPSTREAMS: Record<string, string> = {
    anisette: "https://ani.sidestore.io",
    gsa: "https://gsa.apple.com",
    mobileme: "https://setup.icloud.com",
    findmy: "https://gateway.icloud.com",
};

const CORS_HEADERS = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, POST, PUT, DELETE, OPTIONS",
    "Access-Control-Allow-Headers": "*",
    "Access-Control-Expose-Headers": "*",
};

function pickUpstream(pathname: string): { base: string; rest: string } | null {
    for (const [prefix, base] of Object.entries(UPSTREAMS)) {
        const match = `/${prefix}`;
        if (pathname === match || pathname.startsWith(`${match}/`)) {
            return { base, rest: pathname.slice(match.length) || "/" };
        }
    }
    return null;
}

Deno.serve({ port: PORT }, async (req) => {
    const url = new URL(req.url);

    if (req.method === "OPTIONS") {
        return new Response(null, { status: 204, headers: CORS_HEADERS });
    }

    const target = pickUpstream(url.pathname);
    if (!target) {
        return new Response(
            `Unknown route. Available prefixes: ${Object.keys(UPSTREAMS).join(", ")}`,
            { status: 404, headers: CORS_HEADERS },
        );
    }

    const upstreamUrl = target.base + target.rest + (url.search || "");
    const headers = new Headers(req.headers);
    headers.delete("host");
    headers.delete("origin");
    headers.delete("referer");

    const init: RequestInit = {
        method: req.method,
        headers,
        body: ["GET", "HEAD"].includes(req.method) ? undefined : await req.arrayBuffer(),
        redirect: "manual",
    };

    let upstreamResponse: Response;
    try {
        upstreamResponse = await fetch(upstreamUrl, init);
    } catch (e) {
        return new Response(
            `Upstream fetch failed: ${e instanceof Error ? e.message : e}`,
            { status: 502, headers: CORS_HEADERS },
        );
    }

    const responseHeaders = new Headers(upstreamResponse.headers);
    for (const [k, v] of Object.entries(CORS_HEADERS)) responseHeaders.set(k, v);

    return new Response(upstreamResponse.body, {
        status: upstreamResponse.status,
        statusText: upstreamResponse.statusText,
        headers: responseHeaders,
    });
});

console.log(`TagHistory CORS proxy listening on http://localhost:${PORT}`);
console.log(`Prefixes: ${Object.keys(UPSTREAMS).map((p) => "/" + p).join(", ")}`);
