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
