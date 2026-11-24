# Publishing this repo on GitHub

> **Done:** the repo is live at https://github.com/slidebean/tv-calibrator. Sections 1 to 4 are kept for reference; to work on it
> day to day, see **Everyday use** below.

A step-by-step for putting this folder on GitHub for the first time, then keeping it up to date.
It uses **GitHub Desktop**, a free Mac app that does everything with buttons instead of terminal
commands.

The folder is already set up as a Git repository with nothing committed yet. Your first commit
will be the whole project, signed with the name and email you choose below.

## 1. Set up your GitHub account (once)

1. Sign in at [github.com](https://github.com), or create an account.
2. **Decide where the repo lives.** For a company project, the usual choice is a free
   **organization** called Slidebean, so the address is `github.com/slidebean/tv-calibrator` and
   other people can be given access later without sharing your login. If Slidebean already has one,
   use it. Otherwise click your avatar › **Your organizations** › **New organization** › Free.
3. **Keep your email private.** Every commit carries an email address, and on a public repo anyone
   can read it. Go to **Settings › Emails** and turn on:
   - **Keep my email addresses private.** GitHub then gives you an address like
     `12345678+yourname@users.noreply.github.com`. Note it down.
   - **Block command line pushes that expose my email.**

## 2. Install GitHub Desktop (once)

1. Download it from [desktop.github.com](https://desktop.github.com) and move it to Applications.
2. Open it and sign in with your GitHub account.
3. When it asks you to **Configure Git**, put your name (or "Slidebean") and choose the
   **noreply** email from step 1. You can change this later in **GitHub Desktop › Settings › Git**.

## 3. Make the first commit

1. **File › Add Local Repository…** and choose `Filmmaker Mode/TV Calibrator`.
2. The left column lists every file that will go up, about 130 of them. Scroll through it. You
   should see the app code, `CalibrationKit`, `docs`, `server`, `tools` and `mac-lab`. You should **not** see `.DS_Store`, `xcuserdata` or
   `.git-old-xcode-history`, because `.gitignore` leaves those out.
3. Bottom left, in **Summary**, type `TV Calibrator 1.0` and click **Commit to main**.

## 4. Publish it

1. Click **Publish repository** (top bar).
2. Name: `tv-calibrator`. Description: *Calibrate your TV's picture with your iPhone.*
3. **Keep this code private: leave it ticked for now.** You can switch it to public whenever you're
   ready (step 7).
4. **Organization:** pick Slidebean if you made one.
5. Click **Publish repository**. Click **View on GitHub** to see it.

## 5. Tidy up the page on github.com

On the repo page:

- **About** (the gear icon on the right): add the description, the website (the support page,
  once it's live) and topics such as `ios`, `swiftui`, `tv-calibration`, `filmmaker-mode`, `hdr`.
- **Settings › General › Features:** keep **Issues** on. Turn off **Wiki** and **Projects** unless
  you want them.
- **Settings › General › Social preview:** optional. A 1280 × 640 image shows when the link is
  shared.

## 6. Issue templates (optional)

When someone reports a problem, GitHub can show them a form that asks for their iPhone and TV
model. The two forms are ready in `docs/github-templates/`, but GitHub only reads them from a
hidden `.github` folder, which is easiest to create on the website. Either ask Claude to add them
once the repo is on GitHub, or for each file: on the repo page, **Add file › Create new file**,
type the name `.github/ISSUE_TEMPLATE/bug_report.yml` (the slashes make the folders), paste the
file's contents, and **Commit changes**. Then do the same for `tv_menu.yml`. Afterwards, in
GitHub Desktop, click **Fetch origin** and **Pull**.

The pull request checklist (`docs/pull_request_template.md`) already works where it is.

## 7. Make it public (launch day)

**Settings › General**, scroll to the bottom (**Danger Zone**) › **Change visibility › Make public**.
Before you do, open the repo in the browser and search for `REPLACE-` (press `t` for the file
finder, or use the search box). Every placeholder should be filled in by then.

## Everyday use

- **You change something** (or I change it for you): GitHub Desktop lists the changed files.
  Write a one-line summary, click **Commit to main**, then **Push origin**.
- **Profile or video-link changes** (anything in `server/`): run `npx wrangler deploy` in
  `push-worker/` to publish them (see [HOSTING.md](HOSTING.md)); phones pick them up the next time
  the app opens. Commit and push as usual so GitHub has the history.
- **Someone opens an issue:** you get an email. Reply on GitHub; close it when it's dealt with.
- **Someone sends a pull request** (a proposed change): it appears under **Pull requests**. The
  **Files changed** tab shows exactly what they changed. **Merge** accepts it; then in GitHub
  Desktop click **Fetch origin** and **Pull** to bring it to your Mac.
- **Each App Store release:** on GitHub, **Releases › Draft a new release**, tag it `v1.0`
  (then `v1.1` and so on) and paste the "What's New" text. This marks which code went into which
  App Store version.

## If you'd rather use Xcode

Xcode can do the same: **Settings › Accounts › +** to add GitHub, then **Integrate › Commit** and
**Integrate › Push**. GitHub Desktop is easier to read when many files change at once, which is
why this guide uses it.

## About `.git-old-xcode-history`

This hidden folder holds the Git history Xcode created when you made the project. Its only commit
was Xcode's empty template, signed with your personal email, so it was set aside rather than
published. It isn't uploaded. You can delete it whenever you like.
