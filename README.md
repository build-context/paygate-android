# Paygate Android SDK

Kotlin/Android library mirroring the iOS `Paygate` SDK (WebView paywall + Google Play Billing).

## Build (standalone)

```bash
cd sdks/android
gradle :paygate:assembleRelease
```

Requires `android.useAndroidX=true` (set in `gradle.properties`).

## Integrate in an app

1. Add the `paygate` module (copy or `includeBuild` / Maven publication).
2. In `AndroidManifest.xml`, merging will add `PaygateActivity`.
3. Initialize and launch from a `ComponentActivity` / `Activity`:

```kotlin
lifecycleScope.launch {
    Paygate.initialize(this@MainActivity, apiKey = "pk_...")
    val result = Paygate.launchGate(this@MainActivity, gateId = "gate_...")
    when (result.status) {
        PaygateLaunchStatus.PURCHASED,
        PaygateLaunchStatus.ALREADY_SUBSCRIBED -> { /* unlocked */ }
        else -> { /* declined / limit */ }
    }
}
```

## API version

`Paygate.apiVersion` is `2025-03-16` and must match the backend `Paygate-Version` header.
