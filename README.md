# BAK Importer & MTZ Translator

BAK Importer & MTZ Translator is an Android utility for Xiaomi/MIUI/HyperOS theme backups. It can prepare existing `.bak` theme backups for the system restore screen and translate visible theme text inside `.bak` or `.mtz` archives.

## Features

- Prepare existing BAK theme backups under `MIUI/backup/AllBackup`
- Optionally translate BAK theme text before restore
- Translate MTZ files and save the result to `Downloads/BAK Importer`
- Free on-device translation with Google ML Kit
- Optional Advanced API Translation using the user's own provider and API key
- OpenAI-compatible, Google AI Studio, Vertex AI, Groq, DeepSeek, xAI, Cerebras, Ollama, OpenRouter and Vercel AI Gateway provider profiles
- Context-aware batched requests, translation memory and placeholder validation
- Turkish and English application interface
- Optional local diagnostics for troubleshooting Xiaomi Themes and Backup flows

## Privacy

Theme files are processed on the device. Advanced API Translation is optional. When enabled, only extracted visible strings are sent directly from the device to the provider selected by the user. API keys are encrypted with Android Keystore, excluded from Android backup, and are not written to diagnostics.

The optional diagnostics accessibility service is limited to Xiaomi Themes and MIUI Backup screens while a test is active. Reports are stored locally and are shared only when the user explicitly chooses to share them.

## Build

Requirements:

- Android Studio with Android SDK 36
- JDK 17

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

On Linux or macOS:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The debug APK is generated under `app/build/outputs/apk/debug/`.

## Important notes

- Root, Magisk, Shizuku and ADB privileges are not required by the application.
- Xiaomi may change Themes or Backup behavior between MIUI/HyperOS versions.
- Restoring a backup does not guarantee that Xiaomi Themes will accept every unsigned or modified theme component.
- Users are responsible for their API provider usage and charges.

## Project status

The application is under active development. Please use GitHub Issues for reproducible bugs and include the app version, device/HyperOS version, and a diagnostics package when available. Never post API keys or personal account information.
