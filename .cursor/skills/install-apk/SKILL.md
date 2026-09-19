---
name: install-apk
description: Build and install the VCamdroid debug APK to a USB-connected Android device via adb. Use when the user says install apk, deploy to phone, installDebug, flash the app, or wants a one-click device install.
disable-model-invocation: true
---

# Install APK

Do nothing else. Run one shell command from the repo root and report success or the error output.

Windows:

```powershell
.\android\gradlew.bat -p android installDebug
```

Unix:

```bash
./android/gradlew -p android installDebug
```

Request full permissions if the sandbox blocks adb/gradle. Do not explore the codebase, rebuild the graph, or ask clarifying questions unless the command fails with no device attached.
