# Notifications

The app has two kinds, both opt-in and both switchable in Settings › Notifications and sounds:

| | What | Where it comes from |
|---|---|---|
| **Re-check reminders** | "Time to re-check Living room TV", six months after a finished calibration | Scheduled on the iPhone. Nothing to set up. |
| **New Slidebean videos** | "New on Slidebean: *video title*". Tapping it opens the video | The push worker in `push-worker/`, on Cloudflare |

The app asks once, on a card under the results of a finished calibration (or from Settings), never at
launch. Video pushes stay hidden in the app until the push worker's address is set (step 6).

## How video pushes work

1. When someone turns on notifications, the app sends its push token (an anonymous device address from
   Apple, nothing else) to the worker's `/register`. Turning "New Slidebean videos" off calls `/unregister`.
2. Every 10 minutes the worker looks for a new video on the channel. When one appears (Shorts are
   skipped), it sends the push to every registered iPhone, 40 a minute on the free plan.
3. Tokens Apple reports as gone (app deleted) are removed automatically.

The first run only remembers the videos already there, so turning the worker on doesn't push an old one.

**Public videos only.** The worker sees the channel the way a signed-out visitor does, so private,
scheduled and unlisted videos never show up. With the API key it also checks each new video is public
before announcing it and skips members-only videos. A scheduled video is announced when it goes public.
A video that was unlisted for more than two days before going public is not announced (it counts as old).

**Where it looks.** With a `YOUTUBE_API_KEY` (step 3 below) it asks the YouTube Data API, which is
reliable and also knows when a Premiere hasn't started yet, so the push waits until it's live. Without
one it reads the channel's public feed (`youtube.com/feeds/videos.xml?channel_id=…`). The feed needs
no key, but since late 2025 YouTube's feed server has had stretches of hours when it answers "404 Not
Found" for every channel; during one, a push would simply go out late. If the API ever fails, the
worker falls back to the feed.

## Setup (once)

**1. The channel.** `push-worker/wrangler.toml` has Slidebean's channel ID
(`UC4bq21IPPbpu0Qrsl7LW0sw`, shown on the channel's About page under Share › Copy channel ID).

**2. The APNs key** (Apple's push key; one key works for all your apps).
developer.apple.com › Certificates, Identifiers & Profiles › Keys › **+** › name it "Push", tick
**Apple Push Notifications service (APNs)**, Continue, Register, **Download** the `.p8` file (you can only
download it once). The **Key ID** is the code in the file's name (`AuthKey_KEYID.p8`); your **Team ID**
is at the top right of that page.

Keep the `.p8` file out of this project folder: the project is going on GitHub, and the key lets
anyone send notifications as the app. (`.gitignore` skips `*.p8` as a safety net.)

**3. A YouTube API key** (optional, recommended; free). console.cloud.google.com › create a project ›
APIs & Services › Library › **YouTube Data API v3** › Enable. Then Credentials › Create credentials ›
**API key**, and under "API restrictions" restrict it to YouTube Data API v3. The worker uses about 150
of the 10,000 free requests a day.

**4. Turn on push for the app ID.** In Xcode, open the **TV Calibrator** target › Signing & Capabilities:
**Push Notifications** should be listed (the project already has the entitlement). If Xcode shows a
signing error, click **Try Again**; automatic signing adds push to the app ID.

**5. Deploy the worker.** First, if `push-worker/wrangler.toml` doesn't exist, copy
`wrangler.example.toml` to it and fill in a worker name and a KV namespace id
(`npx wrangler kv namespace create tv-calibrator-push-tokens` makes one and prints its id). Git ignores
`wrangler.toml`, so these stay out of the public repo. Then, in Terminal, from the project folder:

```
cd push-worker
npx wrangler login
npx wrangler deploy
npx wrangler secret put APNS_KEY < ~/Downloads/AuthKey_KEYID.p8    # the file itself, no pasting
npx wrangler secret put APNS_KEY_ID      # the Key ID from step 2
npx wrangler secret put APNS_TEAM_ID     # your Team ID
npx wrangler secret put ADMIN_TOKEN      # any long random password, for /send and /status
npx wrangler secret put YOUTUBE_API_KEY  # the key from step 3, if you made one
```

`deploy` prints the worker's address, e.g. `https://<worker name>.<you>.workers.dev`.

**6. Point the app at it.** Put that address in `TV Calibrator/Resources/ServerConfig.plist` (copy
`Config/ServerConfig.example.plist` there if it's missing). Git ignores the file, so the address stays
out of the public repo. The same address serves the camera profiles (see [HOSTING.md](HOSTING.md)).

## Testing

Run the app from Xcode on your iPhone, finish a calibration (the demo screen doesn't offer the card) or
use Settings › Turn on notifications. Xcode's console prints `Push token: …`. Then:

```
curl -X POST https://<your worker address>/send \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"New on Slidebean","body":"Test push","url":"https://youtu.be/jGk6o8bytjY","token":"PASTE_TOKEN","env":"sandbox"}'
```

Builds run from Xcode use Apple's **sandbox**; TestFlight and the App Store use **production** (the app
tells the worker which). Leave out `token` and `env` to send to everyone (it goes out over the next
minutes). Other admin calls, same header:

- `GET /status`: subscriber count, the last video seen, the send in progress and the last one.
- `POST /check`: look for a new video now instead of waiting for the schedule. The answer says where it
  looked (`"source": "api"` or `"feed"`) or what went wrong.

## Limits and costs

The free Workers plan is enough: 40 pushes a minute (2,400 an hour). With thousands of subscribers,
Workers Paid ($5/month) allows far more per run: set `BATCH = "500"` in `wrangler.toml` and redeploy.
Apple doesn't charge for pushes.

## App Review

Apple allows promotional pushes only when people opt in, in the app's own words, and can turn them off
in the app (guideline 4.5.4). The card says it includes new videos, and Settings has the switch.
