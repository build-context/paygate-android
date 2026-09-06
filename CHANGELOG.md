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
