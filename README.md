# Ninebot F2 Lab

Experimental Android app for an owner-authorized Segway/Ninebot F2-family BLE interoperability test.

## Scope
- Scan/select a nearby scooter over BLE.
- Perform the Ninebot pairing/authentication flow with physical power-button confirmation.
- Verify the ESC/controller before enabling writes.
- Read the speed-limit register `ESC 0x20 / 0x93`.
- Test only 20, 22, 23, 24 or 25 km/h and immediately read the value back.
- No firmware flashing, region/serial modification, motor-current tuning, voltage tuning or brake tuning.
- No raw command console and no blind register scanning.

The speed-limit register mapping is experimental for the F2 Pro D II. A successful Android build does **not** guarantee that stock firmware will accept values above its own regional limit.

## Build
The included GitHub Actions workflow builds `app-debug.apk` automatically on every push to `main` and uploads it as the artifact `ninebot-debug-apk`.

Repository root must directly contain `app/`, `.github/`, `build.gradle.kts`, `settings.gradle.kts`, and `gradle.properties`.

## Test order
1. Connect and authenticate.
2. Read current speed limit.
3. Test 23 first; the app reads it back automatically.
4. Only continue to 24 and 25 if the previous test is accepted and the scooter behaves normally.
5. Use 22 or 20 as a restore value if needed.

Use only where the selected speed is legal and safe.
