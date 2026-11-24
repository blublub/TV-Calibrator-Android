# App Store listing

Everything App Store Connect asks for, with a draft for each field. Copy it in, then edit freely.
Character limits are Apple's; the drafts fit inside them.

## App information (set once)

| Field | Draft | Notes |
|---|---|---|
| Name (30) | `TV Calibrator` | Has to be unique on the App Store. If it's taken, try `TV Calibrator by Slidebean`. |
| Subtitle (30) | `Calibrate your TV with iPhone` | |
| Bundle ID | `com.slidebean.tvcalibrator` | Already set in the Xcode project. |
| SKU | `tvcalibrator-ios` | Private, anything unique. |
| Primary language | English (U.S.) | |
| Category | Primary: **Utilities**. Secondary: **Photo & Video** or **Entertainment**. | Utilities matches the project setting. |
| Content rights | **No**, it doesn't contain third-party content. | The pattern videos are Slidebean's own. |
| Age rating | Answer **No / None** to every question. Expect the lowest rating. | Apple's newer questionnaire also asks about user-generated content, messaging, ads and web access. The app has none of these; it only opens YouTube links in the YouTube app. |
| Price | Free | |
| Availability | All countries | The EU needs trader status first (see the checklist). |

## Privacy

| Field | Answer |
|---|---|
| Privacy policy URL | `https://slidebean.com/tv-calibrator-privacy-policy` (on the Webflow site; `server/privacy/` has the same text) |
| App Privacy ("nutrition label") | **Identifiers › Device ID**: used for App Functionality, not linked to the user, not used for tracking. That's the push token, sent only if someone turns on new-video notifications. Nothing else is collected: no accounts, analytics, ads or tracking. |

## Version page (each release)

**Promotional text (170).** Can be changed any time without a new build.

> Free and open source. Point your iPhone at the TV, play our pattern videos, and get your picture
> to the standard filmmakers grade for. No meter needed.

**Description (4,000)**

> Your TV's picture can look much closer to what the filmmakers intended. Professional calibration
> usually means a colorimeter and an expert. TV Calibrator uses your iPhone's camera as the meter
> and walks you through the settings yourself.
>
> HOW IT WORKS
> • Play our free test-pattern videos on the TV, straight from the YouTube app.
> • Set your iPhone on a stand facing the screen. It reads each pattern from the camera's raw
> sensor data, with focus, exposure and lens locked.
> • The app shows which setting to change in your TV's own menus, which way to move it, and
> when it's on target.
>
> MATCHED TO A PROFESSIONAL METER
> Each supported iPhone model has been measured side by side with a professional colorimeter, so
> its readings mean real colour, not just "brighter" or "bluer". iPhones without their own profile
> use a standard profile, and the app marks those readings as approximate.
>
> GUIDED, STEP BY STEP
> • Before: see how far off your TV is right now.
> • Bright end and dark end: set white balance with Gain and Offset.
> • Colours: check red, green, blue, cyan, magenta and yellow, and bring each one closer.
> • After: before-and-after results you can share.
> • Optional 10- and 20-point fine-tuning for TVs that have it.
>
> MADE FOR YOUR TV
> Menu directions for LG, Samsung, Sony, TCL, Hisense, Panasonic, Philips, Vizio, Toshiba, Sharp
> and Roku TVs. Start with SDR and HDR10. HDR10+ and Dolby Vision are there as advanced steps.
>
> TRY IT WITHOUT A TV
> The demo screen simulates a TV, so you can learn the steps first.
>
> PRIVATE AND FREE
> No account, no ads, no tracking. The camera is only used as a light meter; nothing is recorded
> or uploaded. TV Calibrator is open source, made by Slidebean.

Before submitting, check the description against the build: every brand and step it names should
be in the app.

**Keywords (100, commas, no spaces).** Words in the name and subtitle are already searchable, so
they're not repeated here. Third-party trademarks (Dolby Vision, brand names) are left out on
purpose: App Review sometimes rejects them in keywords.

```
filmmaker mode,calibration,picture,settings,hdr,white balance,color,colour,oled,colorimeter,meter
```

**Support URL:** `https://<your server>/support/` (the page is in `server/support/`)

**Marketing URL** (optional): the YouTube playlist or the GitHub repo.

**Copyright:** `2026 Slidebean`

**What's New** (from the second version on): one or two lines.

## Screenshots

Apple requires the **6.9-inch iPhone** size (1320 × 2868, portrait) and scales it down for
smaller iPhones. Up to 10; 3 to 5 is typical.

The easiest way to make them: run the **TV Calibrator** scheme in the Simulator on an
**iPhone 17 Pro Max**, open the **demo screen**, and press ⌘S at each step. The camera doesn't run
in the Simulator, but the demo does.

Suggested set:

1. Home screen with a screen added.
2. A live step, with the bar moving to green.
3. The Check step's table and chart.
4. Before → after results.
5. The TV brand picker or a menu walkthrough page.

You can add a caption over each in Figma, but plain screenshots are fine.

## App Review information

- **Sign-in required:** No.
- **Contact:** your name, phone and email (Apple only).
- **Notes:** paste the text under "Notes for App Review" in [RELEASE.md](RELEASE.md). It points the
  reviewer to the demo, since they won't have a TV set up.
