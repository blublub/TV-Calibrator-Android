# Releasing to the App Store

Three parts: test the release candidate on real TVs, finish the things the app still points at
placeholders for, then archive and upload.

## 1. Test the release candidate

Build the **TV Calibrator** scheme (not the Lab one) onto the iPhone and go through this once per TV.
Anything that fails here is a bug to fix before uploading.

**First launch**

- The intro shows, then the home screen, then "How it works" opens by itself (only this once; it's
  also on the home screen). The camera card says "Set up the camera".
- Camera setup: start the camera, measure the darkness (phone screen-up on a table), test reading
  on a bright patch. The card on the home screen turns to "Ready to measure".
- No shutter sound while it reads.

**A full SDR pass**

- Add a Screen, pick the brand, answer "Not sure" to one of the menu questions.
- Set up: swipe through the pages. On the last page, open the camera card: it opens as a sheet and
  closing it returns to the same page.
- Before: tap Measure, then start the quick-check video. It should wait through any YouTube ad,
  catch the flashes and count 12 patches.
- Play the wrong video on purpose (an HDR one during SDR): it should stop with "Is the SDR video
  playing?" and save nothing.
- Bright end, dark end: the bars move when you change Gain/Offset and settle to green.
- Check: the table and chart appear. Jump to another step from the bar at the top and come back.
  **Then tap Measure on the Check step and confirm the result lands in Check** (not as a new
  Before). This was a bug in earlier builds.
- Colours: go through at least two; use "Can't get closer" on one.
- Sounds: the first time a bar lands in its green zone (Red gain, a colour's Saturation…) you hear a
  short chime and feel a tap. Push it out of the zone and back: no second chime. Settings › Sounds turns
  it off; the ring switch on silent mutes it.
- After: results show before → after. Tap "Measure again" and confirm the before figures are
  still there. Share the summary image.
- Under the results: the notifications card. Turn them on; Settings › Notifications and sounds shows
  the two switches. A test video push: [PUSH.md](PUSH.md), Testing.

**Fine-tune (a TV with 10/20-point white balance)**

- Run the point finder. After the second measurement you should see "Here's what each control
  moves" and the reminder to put the four controls back. (Earlier builds skipped this page.)

**Interruptions**

- Lock the phone or switch apps for a few seconds during a live step, then come back: readings
  resume within a couple of seconds.
- Do the same during a video measurement: it may fail (that's fine) but must not hang; Cancel works.
- Leave a measurement running and go all the way back to the home screen: it stops.
- Force-quit and relaunch mid-calibration: the screen opens at the step you were on.

**Other**

- HDR10 on the same TV after SDR. The Advanced card asks before HDR10+/Dolby Vision.
- Delete a screen from Manage Screens and from the screen's own menu.
- The demo screen, start to finish, with no TV (this is what App Review will use).
- Largest text size (Settings > Accessibility): nothing important is cut off.

## 2. Before uploading

| What | Where |
|---|---|
| Profile server address | `TV Calibrator/Resources/ServerConfig.plist` (Git ignores it; copy `Config/ServerConfig.example.plist`). Without it the app never contacts a server and uses its built-in profiles. See [HOSTING.md](HOSTING.md). |
| Push worker | `Notifications.pushServer` in `TV Calibrator/App/Notifications.swift`, plus the APNs key: [PUSH.md](PUSH.md). Until it's set the app offers only the re-check reminders. |
| Video links | `server/patterns.json` and the copy in `TV Calibrator/Resources/patterns.json`. While they're empty the app shows video titles but no "Open on YouTube" links. |
| Explainer video | `"explainer"` in the same two files: the Slidebean video that explains calibration. The first-launch "How it works" pages, the home screen and Settings link to it; while it's empty they leave the video out. |
| Camera profiles | Each new iPhone: train with the Lab build, then `tools/build_catalog.py --builtin` (see [ADDING-AN-IPHONE.md](ADDING-AN-IPHONE.md)). |
| Version | Target > General: Version `1.0`, Build `1`. Raise the build number for every upload. |

## 3. Archive and upload

In App Store Connect (once):

1. Certificates, Identifiers & Profiles: register the bundle ID `com.slidebean.tvcalibrator`
   (Xcode's automatic signing usually does this on the first archive).
2. Apps > New App: iOS, the name, primary language, that bundle ID, an SKU of your choice.
3. App Privacy: **Identifiers › Device ID** (App Functionality, not linked, no tracking), for the push token. Add a privacy policy URL (required even so). The video
   pushes store only Apple's anonymous push token, not linked to anyone; check Apple's current App
   Privacy guidance when filling this in, and mention push notifications in the privacy policy.
4. Fill in the listing: subtitle, description, keywords, support URL, category Utilities,
   age rating, screenshots in the iPhone sizes the page asks for.

In Xcode (every upload):

1. Scheme **TV Calibrator**, destination **Any iOS Device (arm64)**.
2. Product > Archive. The archive uses the Release configuration.
3. In the Organizer: Distribute App > App Store Connect > Upload. Keep automatic signing.
4. Wait for the "build processed" email, then add testers in TestFlight (internal testers need no
   review).
5. When the TestFlight build has been through section 1 on real TVs: on the app's version page,
   pick the build, fill in the review notes below, and Submit for Review.

**Notes for App Review** (paste into "Notes"):

> TV Calibrator measures a TV's picture with the iPhone's camera while test-pattern videos play on
> the TV. To review it without a TV: on the home screen tap "Try the demo, no screen needed". The
> demo simulates the readings and gives on-screen buttons in place of the TV's remote.
> The camera is used only as a light meter. No photos are saved, nothing is uploaded, and the app
> has no accounts. The pattern videos are on our YouTube channel (linked in the app).
> Notifications are opt-in, from a card after a finished calibration: a reminder to re-check the TV
> after six months, and a notice when we publish a new video. Both can be turned off in Settings.

## The Lab build

The **TV Calibrator Lab** scheme is for training camera profiles with the Mac tool. It has its own
bundle ID (`com.slidebean.tvcalibrator.lab`) and name ("TV Cal Lab"), so it installs beside the
App Store app. It is never uploaded.
