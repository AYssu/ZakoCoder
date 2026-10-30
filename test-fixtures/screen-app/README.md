# Android screen-test fixture

Disposable native application for ZakoCoder device integration tests. It is a separate Gradle project and is never a production dependency or a preinstalled user project. It contains a counter, text input, greeting, and a synthetic password page.

Build from the repository root with the same JDK/SDK as the application:

```sh
./gradlew -p test-fixtures/screen-app assembleDebug
adb install -r test-fixtures/screen-app/build/outputs/apk/debug/ScreenTestFixture-debug.apk
adb push test-fixtures/screen-app/build/outputs/apk/debug/ScreenTestFixture-debug.apk /data/local/tmp/fixture.apk
```

`-PfixtureMarker=rebuilt` produces different APK bytes with the same package, version and debug signer for artifact identity regression checks. Keep a copy of the baseline APK before rebuilding.

Use an isolated application ID for the ZakoCoder host to protect existing projects/settings. Screen tests require explicit service/root grants for that isolated host. On instrumentation startup Android can restart the host process; enable its accessibility service after instrumentation starts, and use `UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES` throughout accessibility tests. Never change another application's grants or system automation settings permanently. Restore temporary grants and uninstall `dev.zakocoder.screentest.fixture` after validation.

Device classes:

- `ScreenTestControllerDeviceTest`: permission-free boundary checks.
- `AiScreenTestDeviceTest`: local fixture model protocol, approvals, task lifecycle and visual evidence.
- `ScreenTestAccessibilityDeviceTest`: real fixture application actions, Chinese input, assertions, screenshots and password boundaries.
- `ScreenTestRootDeviceTest`: real root actions, failed assertions, stale snapshots and foreground boundaries; needs root allowed for the test host.
- `ScreenTestUiDeviceTest`: light/dark, narrow-screen and enlarged-font screenshots; no system grants.

A green fixture test does not establish compatibility with every application, root manager or Android version. Record the device, permission mode and actual test results separately.
