# Launch checklist

Everything between "the app builds" and "it's on the App Store", in the order it has to happen.
Each step links to the doc with the details.

## 1. Accounts

- [ ] **Apple Developer Program membership is active**, under Slidebean (the organization), not a
      personal account. Check at [developer.apple.com/account](https://developer.apple.com/account).
      It's US$99 a year. An organization membership shows "Slidebean" as the seller on the App
      Store, while a personal one shows your own name. Enrolling as an organization needs a D-U-N-S
      number, which can take a couple of weeks if Slidebean doesn't have one yet.
- [ ] **Agreements accepted.** In App Store Connect › **Business**, the account holder accepts any
      pending agreements. A free app needs no bank or tax details.
- [ ] **EU trader status** (App Store Connect › Business). Apple requires this before an app can
      be offered in the EU. As a company, Slidebean is a trader. Its address, phone and email are
      then shown on the EU App Store page. Until it's verified, untick the EU countries under
      Availability.
- [ ] **Xcode signing team.** The project currently uses team `L527BV674L`. Confirm in
      Xcode › Settings › Accounts that this is the Slidebean team.
- [ ] **GitHub account**, with private email turned on. See
      [PUBLISHING-ON-GITHUB.md](PUBLISHING-ON-GITHUB.md).

## 2. Code on GitHub (private for now)

- [ ] First commit and publish as a **private** repo. See
      [PUBLISHING-ON-GITHUB.md](PUBLISHING-ON-GITHUB.md).

## 3. Server on Cloudflare

The app needs a web address for its camera profiles and video links. The privacy and support
pages live there too.

- [x] Published by the push worker (`npx wrangler deploy` in `push-worker/`). See
      [HOSTING.md](HOSTING.md).
- [ ] Optional: a custom domain, e.g. `calibrator.slidebean.com`. Do this **before** the first
      App Store build, because the address is built into the app.
- [x] The address is in `TV Calibrator/Resources/ServerConfig.plist` (not in Git). Check the file
      exists before archiving: without it the build has no profile downloads or video notifications.
- [ ] Check that `/index.json`, `/privacy/` and `/support/` open in a browser.
- [ ] Set `"help"` in both copies of `patterns.json` to the support page address. The app's
      Settings then links to it.

## 4. Pattern videos

- [ ] SDR and HDR10 videos uploaded to the Slidebean YouTube channel, in two playlists, with the
      exact titles in `server/patterns.json`. See [PATTERN-VIDEOS.md](PATTERN-VIDEOS.md).
- [ ] Each video's link pasted into `server/patterns.json`, plus the two playlist links.
- [ ] HDR10+ and Dolby Vision zips uploaded to Cloudflare R2, with links in `downloads`.
- [ ] The same file copied to `TV Calibrator/Resources/patterns.json`, so the first launch works
      offline.

## 5. Camera profiles

- [ ] Re-check the iPhone 18 Pro profile against the colorimeter (Mac lab tool › "Check the
      profile on this TV").
- [ ] Train and add any other iPhone models you want to ship with. See
      [ADDING-AN-IPHONE.md](ADDING-AN-IPHONE.md).

## 6. Fill in the placeholders

- [ ] Search the whole folder for `REPLACE-`. As of now:
  - the contact email in `server/privacy/index.html` and `server/support/index.html`
  - the GitHub, support and privacy links in `server/support/index.html` and `README.md`
- [ ] Version and build (Target › General). Raise the build number for every upload of the same
      version.

## 7. Test

- [ ] The full test pass in [RELEASE.md](RELEASE.md) § 1, on at least two real TVs, from a
      **TestFlight** build. That way you test exactly what Apple will review.
- [ ] The demo screen, start to finish. This is what App Review will use.

## 8. App Store Connect

- [ ] Create the app record (name, bundle ID, SKU). See [RELEASE.md](RELEASE.md) § 3.
- [ ] Listing, privacy answers, age rating, screenshots. See
      [APP-STORE-LISTING.md](APP-STORE-LISTING.md).
- [ ] Upload the build from Xcode (Product › Archive). Pick it on the version page.
- [ ] Review notes pasted in. Submit for Review. A first review usually takes a day or two.

## 9. Launch day

- [ ] Release the approved version. You can choose manual release when submitting, so you decide
      when it goes live.
- [ ] Make the GitHub repo **public**.
- [ ] GitHub › Releases › new release `v1.0`.
- [ ] Add the App Store link to the README, the support page, the YouTube video descriptions and
      the QR code in the pattern videos' intro.
