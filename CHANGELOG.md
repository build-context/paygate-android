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
