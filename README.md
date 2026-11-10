# Komaki Range Pro — Native Android starter

This is a native Kotlin/Jetpack Compose Android project foundation.

## Included
- Android BLE permissions and GATT scan/connect.
- Discovery of the known Komaki BMS services:
  - `0000FFF0-0000-1000-8000-00805F9B34FB`
  - `00010203-0405-0607-0809-0A0B0C0D1912`
- Read/notification diagnostics, with raw packet logging.
- PackDial-style telemetry UI placeholders.
- A deliberately empty `BmsProtocolAdapter` to integrate a verified decoder later.

## Important limitations
This is a source project, not a compiled APK. The current BMS protocol is not decoded. It does not send any write commands to the BMS. The current source does not yet implement persistent Room history, background GPS foreground service, charging session engine, or PackDial data sharing. These are planned modules, not completed features.

## Build
Open this folder in Android Studio (Giraffe or newer), allow Gradle sync, then Build > Build APK(s). Android SDK 35 and JDK 17 are expected.

## Next technical requirement
Obtain a read-only protocol specification / SDK from Komaki or PackDial, or a legally and safely captured request/response trace. Do not write guessed bytes to the BMS.
