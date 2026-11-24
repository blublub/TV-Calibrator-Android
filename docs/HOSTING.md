# Hosting on Cloudflare

The app downloads three small things: the list of supported iPhones (`index.json`), their camera
profiles (`profiles/`), and links to the pattern videos (`patterns.json`). They're all in this repo's
[`server/`](../server) folder.

There are also the HDR10+ and Dolby Vision pattern downloads (about 100 MB per format).

Everything runs on Cloudflare's free tier.

| What | Where | Why |
|---|---|---|
| `server/` (profiles, links) | The **push worker** (`push-worker/`), as static files | Free, already deployed, one address for everything. `npx wrangler deploy` publishes changes. |
| HDR10+ / Dolby Vision downloads | **Cloudflare R2** (object storage) | Workers limits static files to 25 MiB. R2 has no egress fees and 10 GB free. |

## 1. Publish `server/` with the push worker

The worker that sends the video notifications (`push-worker/`, see [PUSH.md](PUSH.md)) also serves the
`server/` folder as static files (`[assets]` in `push-worker/wrangler.toml`), at the worker's own
address. Requests for a file in `server/` get the file; everything else (`/register`, `/send`…) goes to
the worker's code.

The address isn't in the repo. The app reads it from `TV Calibrator/Resources/ServerConfig.plist`
and the worker's name and storage id are in `push-worker/wrangler.toml`; Git ignores both. To set them
up (once per Mac, or in a fork), copy `Config/ServerConfig.example.plist` and
`push-worker/wrangler.example.toml` to those names and fill them in. Without `ServerConfig.plist` the
app still builds and runs, on its built-in profiles and links, with no video notifications.

To publish a change to `server/` (a new profile, new video links), from the repo folder:

```
cd push-worker
npx wrangler deploy
```

Check it worked: `/index.json` and `/patterns.json` on the worker's address should open in a
browser.

`server/_headers` sets a 5-minute cache, so new profiles and links reach phones quickly.

**Later, automatic:** once the repo is on GitHub, the worker can redeploy itself on every push: in the
Cloudflare dashboard, **Workers & Pages ›** the worker **› Settings › Build**, connect the
repository and set the root directory to `push-worker`.

## 2. R2: the HDR10+ and Dolby Vision downloads

1. In the Cloudflare dashboard, go to **R2 › Create bucket**, e.g. `tv-calibrator-patterns`.
2. Under **Settings › Public access**, connect a custom domain, e.g. `patterns.slidebean.com`. The
   `r2.dev` address works for testing, but Cloudflare rate-limits it and doesn't recommend it for
   production.
3. Upload `HDR10+.zip` and `Dolby Vision.zip`, each a zip of that folder of the pattern pack. The
   dashboard upload is fine; for big files you can use `npx wrangler r2 object put`.
4. Put the links in `server/patterns.json` under `downloads`, then commit and push.

## Changing links without an app update

`server/patterns.json` holds:

- the YouTube playlist links (`playlists.sdr`, `playlists.hdr10`);
- the link to each video, keyed by its exact title (e.g. `"SDR 01 - White (bright end)"`), so the
  app can open the right one;
- the download links (`downloads.hdr10p`, `downloads.dv`).

Edit, commit and push. Phones fetch it each time they open the app.

A copy also ships inside the app (`TV Calibrator/Resources/patterns.json`) for the first launch
and offline use. Update it before each App Store release.

## Moving off AWS

Nothing here depends on AWS. Once the worker and R2 are set up, the app only talks to Cloudflare (and
YouTube, for the videos).
