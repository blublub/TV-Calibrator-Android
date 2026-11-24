// TV Calibrator push worker (Cloudflare Workers + KV).
//
// - The app registers its push token here when the person has turned on "New Slidebean videos".
// - Every few minutes the worker looks for a new video on the Slidebean channel (the YouTube Data API
//   when YOUTUBE_API_KEY is set, else the public feed). When one appears (not a Short), it sends every
//   registered iPhone a notification; tapping it opens the video.
// - POST /send lets you push something by hand (with ADMIN_TOKEN), or test on one iPhone.
//
// Pushes go to Apple (APNs) with a token-based .p8 key. Large sends are spread over successive cron
// runs, BATCH tokens per run, so they stay inside Cloudflare's per-run request limit.
// Setup: docs/PUSH.md.

const FEED = (channel) => `https://www.youtube.com/feeds/videos.xml?channel_id=${channel}`;
const HOSTS = { production: "api.push.apple.com", sandbox: "api.sandbox.push.apple.com" };
const TOKEN_RE = /^[0-9a-f]{64,200}$/;

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    try {
      if (req.method === "POST" && url.pathname === "/register") return await register(req, env);
      if (req.method === "POST" && url.pathname === "/unregister") return await unregister(req, env);
      if (req.method === "POST" && url.pathname === "/send") return await manualSend(req, env);
      if (req.method === "GET" && url.pathname === "/status") return await status(req, env);
      if (req.method === "POST" && url.pathname === "/check") return await adminCheck(req, env);
    } catch (e) {
      return json({ error: String(e && e.message || e) }, 500);
    }
    return new Response("Not found", { status: 404 });
  },

  async scheduled(event, env, ctx) {
    ctx.waitUntil(tick(env, new Date(event.scheduledTime)));
  },
};

// ---------- tokens ----------

async function register(req, env) {
  const { token, env: apns } = await req.json();
  if (!TOKEN_RE.test(token || "")) return json({ error: "bad token" }, 400);
  const e = apns === "sandbox" ? "sandbox" : "production";
  // The APNs environment rides in the key's metadata, so a send reads it from the listing.
  await env.TOKENS.put(`t:${token}`, String(Date.now()), { metadata: { env: e } });
  return json({ ok: true });
}

async function unregister(req, env) {
  const { token } = await req.json();
  if (TOKEN_RE.test(token || "")) await env.TOKENS.delete(`t:${token}`);
  return json({ ok: true });
}

// ---------- admin ----------

function isAdmin(req, env) {
  const h = req.headers.get("authorization") || "";
  return env.ADMIN_TOKEN && h === `Bearer ${env.ADMIN_TOKEN}`;
}

// {title, body, url}: queue a push to everyone. Add "token" (and "env") to send to one iPhone right away.
async function manualSend(req, env) {
  if (!isAdmin(req, env)) return json({ error: "unauthorized" }, 401);
  const m = await req.json();
  if (!m.title || !m.body) return json({ error: "title and body are required" }, 400);
  const msg = { title: String(m.title), body: String(m.body), url: m.url ? String(m.url) : undefined };
  if (m.token) {
    const r = await sendOne(env, m.token, m.env === "sandbox" ? "sandbox" : "production", msg);
    return json(r);
  }
  if (await env.TOKENS.get("job")) return json({ error: "a send is already in progress" }, 409);
  await env.TOKENS.put("job", JSON.stringify({ ...msg, cursor: null, sent: 0, failed: 0, removed: 0, started: Date.now() }));
  return json({ ok: true, queued: true });
}

async function status(req, env) {
  if (!isAdmin(req, env)) return json({ error: "unauthorized" }, 401);
  let n = 0, cursor;
  do {
    const page = await env.TOKENS.list({ prefix: "t:", cursor });
    n += page.keys.length;
    cursor = page.list_complete ? null : page.cursor;
  } while (cursor);
  return json({
    subscribers: n,
    lastVideo: JSON.parse((await env.TOKENS.get("last")) || "null"),
    job: JSON.parse((await env.TOKENS.get("job")) || "null"),
    lastJob: JSON.parse((await env.TOKENS.get("lastJob")) || "null"),
  });
}

// Runs the feed check now (for testing), without waiting for the schedule.
async function adminCheck(req, env) {
  if (!isAdmin(req, env)) return json({ error: "unauthorized" }, 401);
  return json(await checkFeed(env));
}

// ---------- the schedule ----------

async function tick(env, now) {
  const job = JSON.parse((await env.TOKENS.get("job")) || "null");
  if (job) return continueJob(env, job);
  const every = Number(env.FEED_MINUTES || 10);
  if (now.getUTCMinutes() % every === 0) {
    const r = await checkFeed(env);
    if (r.queued) {
      const j = JSON.parse((await env.TOKENS.get("job")) || "null");
      if (j) await continueJob(env, j);
    }
  }
}

// Looks for a new video. The first run only remembers what's already there; after that, a video that
// hasn't been seen before and is less than two days old queues a push to everyone. Videos are
// remembered by id, so deleting the newest one never makes an older one look new.
async function checkFeed(env) {
  const got = await newestVideos(env);
  if (got.error) return got;
  const videos = got.videos;
  if (!videos.length) return { error: "no videos found", source: got.source };
  const seenRaw = await env.TOKENS.get("seen");
  if (!seenRaw) {
    await env.TOKENS.put("seen", JSON.stringify(videos.map((v) => v.id)));
    await env.TOKENS.put("last", JSON.stringify(videos[0]));
    return { newest: videos[0], queued: false, source: got.source, note: "first run: remembered the current videos" };
  }
  const seen = JSON.parse(seenRaw);
  const fresh = videos.filter((v) => !seen.includes(v.id));
  if (!fresh.length) return { newest: videos[0], queued: false, source: got.source };
  if (await env.TOKENS.get("job")) return { newest: videos[0], queued: false, note: "another send is running" };
  let pick = null;
  const members = env.YOUTUBE_API_KEY ? await membersOnly(env) : new Set();
  for (const v of fresh) {                                   // newest first
    // Not public yet, or a premiere or live stream that hasn't started: look again next time.
    if (env.YOUTUBE_API_KEY && !(await isPublicNow(env, v.id))) continue;
    seen.unshift(v.id);
    if (members.has(v.id)) continue;                         // members-only: remembered, never announced
    const ageHours = (Date.now() - Date.parse(v.published)) / 3600e3;
    if (!pick && ageHours < 48) pick = v;
  }
  await env.TOKENS.put("seen", JSON.stringify(seen.slice(0, 60)));
  if (!pick) return { newest: videos[0], queued: false, source: got.source, note: "nothing ready to announce: older than two days, members-only, not public, or a premiere that hasn't started" };
  await env.TOKENS.put("last", JSON.stringify(pick));
  await env.TOKENS.put("job", JSON.stringify({
    title: env.PUSH_TITLE || "New on Slidebean",
    body: pick.title,
    url: `https://www.youtube.com/watch?v=${pick.id}`,
    cursor: null, sent: 0, failed: 0, removed: 0, started: Date.now(),
  }));
  return { newest: pick, queued: true, source: got.source };
}

// The channel's newest videos (no Shorts), newest first: from the YouTube Data API when
// YOUTUBE_API_KEY is set, otherwise (or if that fails) from the public feed. The feed is free and
// needs no key, but YouTube's feed server has stretches of hours when it answers 404.
async function newestVideos(env) {
  if (env.YOUTUBE_API_KEY) {
    const api = await fromApi(env);
    if (!api.error) return api;
    const feed = await fromFeed(env);
    return feed.error ? { error: `${api.error}; ${feed.error}` } : feed;
  }
  return fromFeed(env);
}

const API = "https://www.googleapis.com/youtube/v3";

// The channel's uploads playlist is its id with "UU" in place of "UC"; "UULF" is the long videos only
// and "UUSH" the Shorts only.
async function fromApi(env) {
  const tail = env.CHANNEL_ID.slice(2);
  const list = async (playlist, n) => {
    const r = await fetch(`${API}/playlistItems?part=snippet,contentDetails&maxResults=${n}&playlistId=${playlist}&key=${env.YOUTUBE_API_KEY}`);
    return r.ok ? ((await r.json()).items || []) : null;
  };
  const idOf = (it) => it.contentDetails && it.contentDetails.videoId || it.snippet.resourceId.videoId;
  let items = await list("UULF" + tail, 8);
  if (!items) {
    const all = await list("UU" + tail, 15);
    if (!all) return { error: "YouTube API: could not read the uploads" };
    const shorts = new Set(((await list("UUSH" + tail, 30)) || []).map(idOf));
    items = all.filter((it) => !shorts.has(idOf(it)));
  }
  const videos = items.map((it) => ({
    id: idOf(it),
    title: it.snippet.title,
    published: it.contentDetails && it.contentDetails.videoPublishedAt || it.snippet.publishedAt,
  })).filter((v) => v.id && v.title);
  videos.sort((a, b) => Date.parse(b.published) - Date.parse(a.published));
  return { videos, source: "api" };
}

// True once anyone can watch the video: it's public (not private or unlisted) and not a premiere or live
// stream still waiting to start. If YouTube doesn't answer, the list the video came from is trusted: it
// only holds what a signed-out visitor sees.
async function isPublicNow(env, id) {
  const r = await fetch(`${API}/videos?part=snippet,status&id=${id}&key=${env.YOUTUBE_API_KEY}`);
  if (!r.ok) return true;
  const item = ((await r.json()).items || [])[0];
  if (!item) return false;
  if (item.snippet.liveBroadcastContent === "upcoming") return false;
  return !item.status || item.status.privacyStatus === "public";
}

// The ids of the channel's members-only videos (the "UUMO" playlist), so they are never announced.
async function membersOnly(env) {
  try {
    const r = await fetch(`${API}/playlistItems?part=contentDetails&maxResults=50&playlistId=UUMO${env.CHANNEL_ID.slice(2)}&key=${env.YOUTUBE_API_KEY}`);
    if (!r.ok) return new Set();
    return new Set(((await r.json()).items || []).map((it) => it.contentDetails && it.contentDetails.videoId));
  } catch {
    return new Set();
  }
}

async function fromFeed(env) {
  const res = await fetch(FEED(env.CHANNEL_ID), { headers: { "user-agent": "TVCalibratorPush/1.0" } });
  if (!res.ok) return { error: `YouTube feed answered ${res.status}` };
  return { videos: feedVideos(await res.text()), source: "feed" };
}

// The feed's entries that aren't Shorts. The feed lists newest first.
function feedVideos(xml) {
  const out = [];
  for (const m of xml.matchAll(/<entry>([\s\S]*?)<\/entry>/g)) {
    const e = m[1];
    const href = (e.match(/<link rel="alternate" href="([^"]+)"/) || [])[1] || "";
    if (href.includes("/shorts/")) continue;
    const id = (e.match(/<yt:videoId>([^<]+)<\/yt:videoId>/) || [])[1];
    const title = (e.match(/<title>([\s\S]*?)<\/title>/) || [])[1];
    const published = (e.match(/<published>([^<]+)<\/published>/) || [])[1];
    if (id && title) out.push({ id, title: unescapeXml(title.trim()), published });
  }
  return out;
}

function unescapeXml(s) {
  return s.replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#39;/g, "'")
    .replace(/&apos;/g, "'").replace(/&#(\d+);/g, (_, n) => String.fromCodePoint(Number(n))).replace(/&amp;/g, "&");
}

// Sends to the next BATCH tokens, then saves where it got to. The next cron run carries on.
async function continueJob(env, job) {
  const batch = Math.max(1, Math.min(Number(env.BATCH || 40), 1000));
  const page = await env.TOKENS.list({ prefix: "t:", cursor: job.cursor || undefined, limit: batch });
  const msg = { title: job.title, body: job.body, url: job.url };
  const results = await Promise.all(page.keys.map((k) =>
    sendOne(env, k.name.slice(2), (k.metadata && k.metadata.env) || "production", msg)));
  for (const r of results) {
    if (r.ok) job.sent++;
    else {
      job.failed++;
      if (r.gone) { await env.TOKENS.delete(`t:${r.token}`); job.removed++; }
    }
  }
  if (page.list_complete) {
    await env.TOKENS.put("lastJob", JSON.stringify({ ...job, cursor: undefined, finished: Date.now() }));
    await env.TOKENS.delete("job");
  } else {
    job.cursor = page.cursor;
    await env.TOKENS.put("job", JSON.stringify(job));
  }
}

// ---------- APNs ----------

async function sendOne(env, token, apnsEnv, msg) {
  const jwt = await apnsJwt(env);
  const payload = { aps: { alert: { title: msg.title, body: msg.body }, sound: "default" } };
  if (msg.url) payload.url = msg.url;
  const res = await fetch(`https://${HOSTS[apnsEnv] || HOSTS.production}/3/device/${token}`, {
    method: "POST",
    headers: {
      authorization: `bearer ${jwt}`,
      "apns-topic": env.BUNDLE_ID,
      "apns-push-type": "alert",
      "apns-priority": "5",
      "apns-expiration": String(Math.floor(Date.now() / 1000) + 2 * 86400),
    },
    body: JSON.stringify(payload),
  });
  if (res.ok) return { ok: true, token };
  let reason = "";
  try { reason = (await res.json()).reason || ""; } catch (_) {}
  // The app was deleted or the token is no longer valid: forget it.
  const gone = res.status === 410 || reason === "BadDeviceToken" || reason === "Unregistered";
  return { ok: false, token, status: res.status, reason, gone };
}

// Apple wants a fresh signed token at most every hour and no more than every 20 minutes: keep one for 50.
let cachedJwt = null;
async function apnsJwt(env) {
  const now = Math.floor(Date.now() / 1000);
  if (cachedJwt && now - cachedJwt.iat < 3000) return cachedJwt.token;
  const stored = JSON.parse((await env.TOKENS.get("jwt")) || "null");
  if (stored && now - stored.iat < 3000) { cachedJwt = stored; return stored.token; }
  const header = b64url(JSON.stringify({ alg: "ES256", kid: env.APNS_KEY_ID }));
  const claims = b64url(JSON.stringify({ iss: env.APNS_TEAM_ID, iat: now }));
  const key = await crypto.subtle.importKey("pkcs8", pemToDer(env.APNS_KEY), { name: "ECDSA", namedCurve: "P-256" },
    false, ["sign"]);
  const sig = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key,
    new TextEncoder().encode(`${header}.${claims}`));
  const token = `${header}.${claims}.${b64url(new Uint8Array(sig))}`;
  cachedJwt = { token, iat: now };
  await env.TOKENS.put("jwt", JSON.stringify(cachedJwt));
  return token;
}

function pemToDer(pem) {
  const b64 = String(pem).replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
}

function b64url(input) {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : input;
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), { status, headers: { "content-type": "application/json" } });
}
