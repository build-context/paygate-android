## 0.5.0

- **Breaking.** `DistributionChannel.TESTFLIGHT` is now
  `DistributionChannel.TESTING`, and the wire value is `testing`. It was an
  Apple brand name standing in for a platform-neutral idea, and it was never
  reachable from Android at all: this SDK only ever computed `PRODUCTION` or
  `DEBUG`, so a gate's TestFlight settings applied to nobody here.
- **`currentChannel` can now return `TESTING`.** A release build whose installer
  is not Play — sideloaded, `adb install`, a locally built AAB — is a build
  under test rather than a member of the public, and reporting it as production
  is why a console edit looked like it had not taken: production caches, so the
  paywall was fetched once and reused for the process.
- **`Paygate.channelOverride` sets the channel explicitly, and wins over
  everything.** This is the only way to mark a **Play internal/closed/open
  testing** build, because Play tells an installed app nothing about which track
  served it — every track reports `com.android.vending`, exactly like
  production, and there is no track API. iOS has no such problem (a TestFlight
  install carries a `sandboxReceipt`), so the asymmetry is Google's. Set it from
  something known at build time: a flavor, a `BuildConfig` field.
- Unlike `storefrontOverride`, `channelOverride` is **not** refused in
  production. It selects among your own gate settings; it cannot reprice
  anything, and it cannot reveal a paywall a gate has switched off.
- Install-source detection fails toward `PRODUCTION`: if the installer cannot be
  read (an OEM restriction, a stricter profile), the safe reading is a shipped
  app. A null installer is *not* a read failure — that is a genuine sideload,
  and it reports `TESTING`.
- Requires an API deployed on or after this release. An older API serves
  `testflight`, which this build no longer matches; the gate then falls back to
  shown-and-cached rather than hidden, so a version skew costs refresh-on-launch
  and never a sale.

## 0.4.0

- **Breaking.** `GateData.enabledChannels` and `GateData.launchCache` are gone,
  replaced by `channels` — one entry per distribution channel carrying both
  whether the gate shows there and how it caches there. Caching is per channel
  because that is where it varies: `refresh_on_launch` on debug so flow edits
  appear without a reinstall, `cache_on_first_launch` in production so the
  paywall does not wait on the network. One gate-wide value made that
  combination impossible.
- Reads `Paygate-Version: 2026-09-07`. The backend still serves the previous
  version, so already-shipped builds are unaffected — but this build requires an
  API deployed on or after 2026-09-07.
- Prices can now resolve to the reader's own store country. The SDK reports its
  storefront and the server renders `{getProduct(x).price[<key>]}` for it,
  falling back to the named key when that country is not configured. Detection
  never blocks a launch: if the store has not answered yet the fallback price
  renders rather than the paywall waiting.
- Flow and gate caches are keyed by storefront. Without that, a gate set to
  `cache_on_first_launch` would serve whichever country opened it first to
  everyone after.
- `storefrontOverride` previews another country while testing. It is refused on
  the `production` channel and the server logs that it was.
- Storefront comes from Play `getBillingConfigAsync`, re-read on every launch
  because a user can change Play country mid-session. It needs a connected
  BillingClient and returns null rather than waiting when one is not up.

## 0.3.1

- **Fix: `Paygate.initialize` crashed the app on Play Billing 8.** The client was
  built with the no-arg `enablePendingPurchases()`, which was deprecated in
  Billing 6.2 and **removed in 8.0.0**. This module compiles against 7.1.1, where
  the method still exists, so nothing failed to build — it surfaced only at
  runtime, as a `NoSuchMethodError` on the main thread, and only in host apps
  that put a newer billing library on the classpath.
- Any app also using Flutter's `in_app_purchase` is such a host: its Android
  package requires `billing:8.0.0`, Gradle resolves to the highest version, and
  this SDK is handed a `BillingClient.Builder` without the method it was
  compiled against. The app died at launch before any paywall could open.
- Now uses the `PendingPurchasesParams` form, which exists from 6.2 onward — so
  it still compiles here and works on 7 and 8 alike.

## 0.3.0

- Gates can pin a flow's colour scheme. A WebView reads `prefers-color-scheme`
  from the system night mode rather than from your app, so an app with its own
  light/dark setting could show a paywall that disagreed with the screen behind
  it. The gate's `appearance` now decides, and the app can override it per
  launch — only the app knows whether it has a theme preference of its own.
- `appearance` defaults to `system`, which is exactly what every existing gate
  already does, so nothing restyles without being asked.
- `launchGate` and `launchFlow` take an optional `appearance`. The WebView is
  built on a configuration context carrying the requested night-mode bits, which
  is what actually changes which media queries match — injected CSS cannot do
  that.

## 0.2.0

- **Fix: subscription purchases could use the wrong base plan.** The billing flow
  took the first entry of `subscriptionOfferDetails`, which spans every base plan
  on a subscription id and every offer within each. On a subscription with more
  than one base plan the cadence charged was whatever Play listed first, and a
  promotional offer could be selected in place of the standing price.
- Products now carry `playBasePlanId`. When set, the purchase flow narrows to
  that base plan and prefers its plain price; when it names a base plan Play does
  not have, the purchase fails loudly instead of billing a different one.
- A subscription with no offers now throws `ProductNotFound` rather than starting
  a billing flow with no offer token, which Play rejects anyway.
- `BillingManager.purchase` takes a new trailing `basePlanId` parameter. It
  defaults to null, so existing calls compile unchanged and keep the previous
  first-offer behavior.

## 0.1.8

- Point default base URL at the paygate-prod-bc API host

## 0.1.7

- Rename module from paygate-sdk to paygate
- Publish to Maven (GitHub Packages)

## 0.1.5

- Initial public release
- Google Play Billing integration, WebView paywall presentation
- Gate and flow launching
