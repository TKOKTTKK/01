/**
 * Cloudflare Worker：静态页 + tronagg 同源转发
 *
 * 前端只在同源上调这三个路径，不跨域：
 *   POST /api/    -> https://tronagg.ai/api/v1/buy
 *   GET  /api2/   -> https://tronagg.ai/api/v1/orders
 *   GET  /api3/   -> https://tronagg.ai/api/v1/estimate
 * 其它路径交给静态资源（你的 index.html）。
 *
 * API Key：优先用 Worker 环境变量 / Secret 里的 TRONAGG_KEY；
 *          没配置时透传前端带来的 X-API-Key。
 */

const UPSTREAM = "https://tronagg.ai";

const ROUTES = {
  "/api": "/api/v1/buy",
  "/api/": "/api/v1/buy",
  "/api2": "/api/v1/orders",
  "/api2/": "/api/v1/orders",
  "/api3": "/api/v1/estimate",
  "/api3/": "/api/v1/estimate",
};

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const target = ROUTES[url.pathname];

    // 预检（同源其实用不到，留个兜底）
    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Headers": "Content-Type, X-API-Key, Idempotency-Key",
          "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
        },
      });
    }

    // 不是接口路径 -> 静态文件
    if (!target) {
      if (env && env.ASSETS && typeof env.ASSETS.fetch === "function") {
        return env.ASSETS.fetch(request);
      }
      return new Response("Not Found", { status: 404 });
    }

    const upstreamURL = UPSTREAM + target + url.search;

    const headers = new Headers();
    const ct = request.headers.get("Content-Type");
    if (ct) headers.set("Content-Type", ct);
    const accept = request.headers.get("Accept");
    if (accept) headers.set("Accept", accept);
    const idem = request.headers.get("Idempotency-Key");
    if (idem) headers.set("Idempotency-Key", idem);

    const key = (env && env.TRONAGG_KEY) || request.headers.get("X-API-Key");
    if (key) headers.set("X-API-Key", key);

    const init = { method: request.method, headers, redirect: "follow" };
    if (request.method !== "GET" && request.method !== "HEAD") {
      init.body = await request.arrayBuffer();
    }

    let res;
    try {
      res = await fetch(upstreamURL, init);
    } catch (e) {
      return new Response(
        JSON.stringify({ error: "upstream_unreachable", message: String((e && e.message) || e) }),
        { status: 502, headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } }
      );
    }

    const out = new Headers();
    out.set("Content-Type", res.headers.get("Content-Type") || "application/json");
    out.set("Cache-Control", "no-store");

    return new Response(res.body, { status: res.status, headers: out });
  },
};
