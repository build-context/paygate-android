## 0.7.1

- **A purchase made in a gate is credited to that gate.** After a purchase
  completes inside `launchGate`, the SDK reports which gate and paywall sold
  it (`POST /sdk/purchases`, with the purchase token). Paygate pairs that with
  Play's Real-time Developer Notification, which it checks against Play for
  the price, so revenue shows under the gate and its price-test arm instead of
  as unattributed. Before this nothing reported it.
- The report carries no price, and it is saved to disk before it is sent, so
  a killed or offline app still sends it on its next launch. Restores are not
  reported.
- No API changes.

## 0.7.0

- **Restore is never silent.** When a paywall's Restore button finds nothing,
  or Play cannot be reached, the SDK tells the page: `paygate.restore()` now
  returns a Promise, and a flow can set `paygate.onRestore = fn` to receive
  `{ status: "restored" | "nothing_found" | "error" }`. A flow with no handler
  gets a native dialog for `nothing_found` and `error`, so existing flows
  don't need editing.
- **A Play failure no longer reads as "owns nothing".** A purchase query that
  failed, or ran on a dropped connection, used to replace the owned set with
  an empty one. Restore then reported nothing, and an app that trusts an empty
  answer revoked a paying user. Now the SDK reconnects first, checks Play's
  answer, and keeps the last known set when Play can't answer. Restore reports
  an error in that case.
- **Restored purchases are acknowledged.** Play refunds a purchase that is
  still unacknowledged after three days. Purchases found by a query (a
  restore, or a purchase that finished while the app was closed) were never
  acknowledged. Now they are.
- A purchase counts as owned the moment Play confirms it, before the
  follow-up query lands.
- **`Paygate.getActiveEntitlementProductIds()`** is the new name for what the
  user owns: subscriptions and one-time products (lifetime unlocks), as on
  Android it always was. `getActiveSubscriptionProductIds()` is deprecated and
  returns the same set.
- **`Paygate.restorePurchases()`** for a "Restore purchases" row in your own
  settings. It returns what the user owns, and throws when Play can't answer.
- A second tap on Restore while one is running is ignored.

## 0.6.0

- **A gate now shows each reader the same flow every time.** The SDK creates a
  random install id on first use, keeps it in SharedPreferences, and sends it
  as `Paygate-Install-Id` on every request. The server hashes it with the gate
  id to pick the flow, so a reader no longer re-rolls the gate's weights on
  every cold launch — or, with `refresh_on_launch`, on every presentation.
  That is what makes a price test readable: one person, one price.
- **`Paygate.appUserId`** (optional) assigns by your own user id instead, so
  one person gets the same flow across devices and reinstalls. Sent as
  `Paygate-App-User-Id`; it wins over the install id. Setting it moves a reader
  once, from their install's flow to their user's — set it before the first
  gate launch if you can. Use an opaque id, not an email address.
- **`Paygate.installId(context)`** exposes the install id for your own logs and
  support. It is a UUID this SDK made up, not an advertising or device
  identifier, and clearing the app's data discards it.
- Changing a gate's weights still moves some readers to a different flow; see
  the gate docs. Older SDKs keep the random per-fetch pick.

## 0.5.4

- **A transient refusal while reconnecting is retried, not final.** When Play
  answers the reconnect with SERVICE_UNAVAILABLE, SERVICE_DISCONNECTED,
  NETWORK_ERROR or ERROR, the purchase retries with backoff (1s, 2s, 4s) inside
  an 8-second budget instead of failing on the first answer — the same set and
  shape Superwall's and RevenueCat's Android SDKs retry. BILLING_UNAVAILABLE,
  DEVELOPER_ERROR and FEATURE_NOT_SUPPORTED still fail at once: they are
  verdicts, and asking again only makes the reader wait for the same answer.

## 0.5.3

- **Fix: Buy failed with "Billing service not connected" after Play dropped
  the connection.** The client connected once at `initialize` and nothing ever
  reconnected it, so a routine drop — a Play Store self-update, the service
  reclaimed in the background — left every purchase failing until the app was
  killed. The client now enables Play's automatic service reconnection
  (Billing 8.0.0+), and a purchase that finds the client down reconnects and
  waits up to 5 seconds instead of failing on the spot. Concurrent callers
  share one connection attempt, since Play answers a second `startConnection`
  during `CONNECTING` with DEVELOPER_ERROR.
- A device Play genuinely refuses now fails with Play's own response code and
  message (e.g. BILLING_UNAVAILABLE on a device with no Play account — Play's
  pre-launch test devices are exactly this) rather than a bare "not connected".
- **Fix: "no such product" was reported as "Play did not answer".**
  `withTimeoutOrNull` returns null for a timeout *and* for a lookup that found
  nothing, so a missing SUBS product threw the timeout's error and the INAPP
  lookup never ran. One-time products could not be bought at all.
- First unit tests for the SDK (`./gradlew :paygate:testReleaseUnitTest`),
  covering the connection and lookup paths against a fake `BillingClient`.

## 0.5.2

- **Fix: tapping Buy did nothing in any app that also uses Play Billing 8.**
  This SDK compiled against Billing 7.1.1, but `ProductDetailsResponseListener`
  changed incompatibly in 8.0.0:

      7.1.1  onProductDetailsResponse(BillingResult, List<ProductDetails>)
      8.0.0  onProductDetailsResponse(BillingResult, QueryProductDetailsResult)

  Gradle resolves a version conflict by taking the highest, so any host app
  depending on Billing 8 — every app using Flutter's `in_app_purchase` 0.5.x,
  which requires exactly 8.0.0 — put 8 on the classpath no matter what this
  module was built against. The compiled lambda was then not an implementation
  of the interface the client invoked, and the callback died with
  `AbstractMethodError` inside Play's own thread.

  Nothing surfaced: no throw on any thread the SDK owns, so 0.5.1's new
  8-second timeout was the only thing that fired, and the reader who tapped Buy
  watched the paywall stall and close. It reads exactly like a dead button, and
  it sends you to Play Console to hunt a product that was never missing.

  `billing-ktx` is now pinned to **8.0.0** — a floor, not a preference. Lowering
  it reintroduces the same trap in the other direction.
- Product lookups log `unfetchedProductList` when Play answers OK with an empty
  list. Billing 8 says *why* per product, which separates "no such product"
  from "exists but is not purchasable for you" — previously the same empty list.

## 0.5.1

- **Fix: tapping Buy could do nothing at all, forever, with nothing in the log.**
  `queryProductDetailsAsync` does not always call its listener — a client that
  reports `isReady` can still have its query silently dropped by Play, and
  `suspendCancellableCoroutine` then waited for a callback that was never
  coming. The purchase never failed, so nothing was ever reported: the paywall
  just sat there. Product lookups now time out after 8s and raise
  `PaygateException.BillingUnavailable`.
- **Four other silent paths made loud.** `purchase()` returned a bare `null`
  when the BillingClient was missing and when `launchBillingFlow` refused,
  `PurchasesUpdatedListener` mapped every billing error onto the same `null` as
  a user cancellation, and `handlePurchase` had no `else` at all. A failed
  purchase was indistinguishable from a declined one and from a dead button.
- **`null` from `purchase()` now means a user cancellation and nothing else.**
  Every other outcome throws, so the host app gets a `PaygateResult.Error` it
  can act on rather than silence. A cancellation still leaves the paywall up,
  which is correct — they closed Play's sheet, not your screen.
- Response codes are logged by name, with the two that actually happen spelled
  out: `DEVELOPER_ERROR` (a locally-signed build of an app on Play App Signing)
  and `ITEM_UNAVAILABLE` (product not live for that account, country or track).
- Bridge actions are traced at debug level. The shim that feeds the bridge
  swallows its own errors, so without it there was no way to tell a tap that
  never arrived from one that arrived and did nothing.

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
