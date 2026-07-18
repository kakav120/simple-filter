# Simple Filter — build the APK from your phone

This is a one-button app: tap "Turn On" and it routes DNS through
Cloudflare's family-safe filter (1.1.1.3 / 1.0.0.3), which blocks known
adult-content domains network-wide. No accounts, no complicated setup.

## Steps (all from your phone's browser — Chrome or Safari, not the GitHub app)

1. Go to **github.com**, sign up or log in.
2. Tap the **+** icon → **New repository**. Name it `simple-filter`, set it
   to Public, tap **Create repository**.
3. On the empty repo page, tap **"uploading an existing file"**.
4. You need to upload this whole folder structure, preserving paths. The
   easiest way on mobile: tap **choose your files**, and your phone's file
   picker will let you multi-select — but GitHub's web uploader does NOT
   preserve folder structure well from a raw multi-select on mobile.
   **Recommended instead:** request the desktop site in your browser
   (Chrome menu → "Desktop site") before uploading — this lets you drag a
   whole folder in one action and GitHub preserves the paths.
5. Once every file is uploaded matching the structure below, tap
   **Commit changes**.
6. Go to the **Actions** tab of your repo. You'll see a build running
   automatically (triggered by the workflow file). Wait 2–4 minutes.
7. When it finishes (green checkmark), tap into the completed run, scroll
   to **Artifacts**, and tap **simple-filter-apk** to download it.
8. Open the downloaded `.zip`, extract `app-debug.apk`, tap it, allow
   "install unknown apps" for your browser when prompted, and install.

## File structure to upload (must match exactly)

```
simple-filter/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── .github/
│   └── workflows/
│       └── build.yml
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/simpleblock/filter/
        │   ├── MainActivity.kt
        │   ├── DnsFilterVpnService.kt
        │   └── BootReceiver.kt
        └── res/
            ├── layout/activity_main.xml
            ├── values/strings.xml
            ├── values/themes.xml
            ├── values/colors.xml
            ├── drawable/ic_launcher_foreground.xml
            └── mipmap-anydpi-v26/ic_launcher.xml
```

## What this app actually does (and doesn't do)

- Turns on a local VPN that only intercepts DNS lookups (port 53) and
  answers them via Cloudflare's family filter instead of your normal DNS —
  everything else (your regular browsing traffic) bypasses the VPN
  untouched.
- Has an on/off toggle. There's no uninstall-blocking, no accountability
  partner, no lockdown — this is deliberately the simple version. DNS
  filtering can be bypassed by anyone who knows to turn the toggle off,
  change DNS manually, or use an app with hardcoded DNS (rare, but some
  browsers ship with DNS-over-HTTPS that ignores system DNS settings).
- If you want it harder to turn off yourself later, look at the earlier,
  more locked-down version we discussed, or a maintained app like Bark or
  Covenant Eyes.
