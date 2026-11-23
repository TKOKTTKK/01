/**
 * Cloudflare Worker：静态页 + tronagg 同源转发 + TronGrid 同源转发
 *
 * 目标：两把密钥都只存在于这份 worker.js（服务端，访客看不到），
 *      页面源码里一把都不出现。
 *
 * 路由：
 *   POST /api/        -> https://tronagg.ai/api/v1/buy
 *   GET  /api2/       -> https://tronagg.ai/api/v1/orders
 *   GET  /api3/       -> https://tronagg.ai/api/v1/estimate
 *   ANY  /trongrid/*  -> https://api.trongrid.io/*        （自动补 TRON-PRO-API-KEY）
 *   其它路径          -> 静态资源（index.html）
 *
 * 密钥优先级：环境变量 / Secret 优先，没有就用下面写死的默认值。
 * 想换 key：改下面两行常量重新 deploy，或者用 wrangler secret put 覆盖（不用改文件）。
 */

/* ====== 两把密钥（可以改成空字符串 "" 全部改用 Secret） ====== */
const TRONAGG_KEY_DEFAULT = "ta_iICQNYTwnFFuh_69-V9lHaPEs3kdBI1dA4a_fKJnOaI";
const TRONGRID_KEY_DEFAULT = "ac3db086-20f7-49fa-9e28-8437ec54fe9c";
/* ============================================================ */

const TRONAGG = "https://tronagg.ai";
const TRONGRID = "https://api.trongrid.io";

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

    if (request.method === "OPTIONS") {
      return new Response(null, {
        status: 204,
        headers: {
          "Access-Control-Allow-Origin": "*",
          "Access-Control-Allow-Headers": "Content-Type, X-API-Key, Idempotency-Key, TRON-PRO-API-KEY",
          "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
        },
      });
    }

    const envOf = env || {};

    /* ---------- TronGrid 转发（页面所有链上请求走这里） ---------- */
    if (url.pathname === "/trongrid" || url.pathname.startsWith("/trongrid/")) {
      const rest = url.pathname.slice("/trongrid".length) || "/";
      return proxy(request, TRONGRID + rest + url.search, {
        "TRON-PRO-API-KEY": envOf.TRONGRID_KEY || TRONGRID_KEY_DEFAULT,
      }, { passCache: true });
    }

    /* ---------- tronagg 转发 ---------- */
    const target = ROUTES[url.pathname];
    if (!target) {
      if (envOf.ASSETS && typeof envOf.ASSETS.fetch === "function") {
        return envOf.ASSETS.fetch(request);
      }
      return new Response("Not Found", { status: 404 });
    }

    return proxy(request, TRONAGG + target + url.search, {
      "X-API-Key": envOf.TRONAGG_KEY || TRONAGG_KEY_DEFAULT,
    });
  },
};

/**
 * 统一转发：不透传浏览器带来的任何密钥头，只用服务端这份。
 */
async function proxy(request, upstreamURL, extraHeaders, opts) {
  const headers = new Headers();
  const ct = request.headers.get("Content-Type");
  if (ct) headers.set("Content-Type", ct);
  const accept = request.headers.get("Accept");
  if (accept) headers.set("Accept", accept);
  const idem = request.headers.get("Idempotency-Key");
  if (idem) headers.set("Idempotency-Key", idem);
  for (const k in extraHeaders) {
    if (extraHeaders[k]) headers.set(k, extraHeaders[k]);
  }

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
  if (opts && opts.passCache) {
    const cc = res.headers.get("Cache-Control");
    if (cc) out.set("Cache-Control", cc);
  } else {
    out.set("Cache-Control", "no-store");
  }

  return new Response(res.body, { status: res.status, headers: out });
}
