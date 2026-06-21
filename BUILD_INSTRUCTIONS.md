# FreeConnect for Bedrock — Build Instructions

## Requirements

| Tool | Minimum Version |
|------|----------------|
| Android Studio | Hedgehog (2023.1.1) or newer |
| JDK | 17 (bundled with Android Studio) |
| Android SDK | API 35 (compileSdk) |
| Gradle | 8.7 (auto-downloaded by wrapper) |

---

## 1. Clone / Open the Project

```bash
# If cloned from Git:
git clone <repo-url>
cd FreeConnectForBedrock

# Or open via Android Studio → File → Open → select this folder
```

---

## 2. Sync Gradle

Android Studio will prompt you to **Sync Now** automatically.  
If it doesn't, run:

```
File → Sync Project with Gradle Files
```

Or from the terminal:

```bash
./gradlew build --info
```

---

## 3. Run on a Device / Emulator

1. Connect an Android device (API 26+) or start an emulator.
2. Click the **Run ▶** button in Android Studio,  
   or from the terminal:

```bash
./gradlew installDebug
```

---

## 4. Build a Debug APK

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

---

## 5. Build a Release APK (Signed)

### 5a. Generate a keystore (first time only)

```bash
keytool -genkey -v \
  -keystore keystore/freeconnect.jks \
  -alias freeconnect \
  -keyalg RSA -keysize 2048 \
  -validity 10000
```

### 5b. Configure signing in `app/build.gradle.kts`

Uncomment the `signingConfigs.release` block and fill in your keystore details,
or provide values via environment variables:

```bash
export KEYSTORE_PASSWORD=yourPassword
export KEY_ALIAS=freeconnect
export KEY_PASSWORD=yourKeyPassword
```

### 5c. Assemble the release APK

```bash
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

---

## 6. Build an App Bundle (for Play Store)

```bash
./gradlew bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`

---

## Project Structure

```
FreeConnectForBedrock/
├── app/
│   ├── build.gradle.kts          ← app-level Gradle config
│   ├── proguard-rules.pro        ← release shrinking rules
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/freeconnect/bedrock/
│       │   ├── FreeConnectApplication.kt   Hilt entry point
│       │   ├── MainActivity.kt             Single-activity host
│       │   ├── data/
│       │   │   ├── db/                     Room entities & DAOs (servers)
│       │   │   └── resourcepack/           Room entities, DAOs, repo (packs)
│       │   ├── di/
│       │   │   └── AppModule.kt            Hilt singleton providers
│       │   ├── network/
│       │   │   ├── LanBroadcaster.kt       UDP LAN advertisement
│       │   │   └── ResourcePackProxy.kt    UDP proxy for pack injection
│       │   ├── service/
│       │   │   └── LanBroadcastService.kt  Foreground service
│       │   └── ui/
│       │       ├── navigation/AppNavGraph.kt
│       │       ├── theme/                  Material 3 dark/light theme
│       │       ├── home/                   Server list screen + VM
│       │       ├── addserver/              Add/Edit server screen + VM
│       │       ├── settings/               Settings screen + VM
│       │       └── resourcepack/           Pack management screen + VM
│       └── res/
│           ├── values/strings.xml
│           ├── values/themes.xml
│           └── xml/network_security_config.xml
├── build.gradle.kts              ← root Gradle config
├── settings.gradle.kts
├── gradle/
│   ├── libs.versions.toml        ← version catalog (all deps in one place)
│   └── wrapper/gradle-wrapper.properties
└── gradle.properties
```

---

## Architecture

| Layer | Technology |
|-------|-----------|
| UI | Jetpack Compose + Material 3 |
| Navigation | Jetpack Navigation Compose |
| State | ViewModel + StateFlow |
| Dependency Injection | Hilt |
| Local Database | Room (SQLite) |
| Preferences | DataStore |
| Async | Kotlin Coroutines |
| JSON (backup) | Gson |
| Networking | Raw UDP (DatagramSocket) |

---

## Resource Pack Feature — How It Works

The app implements a **local UDP proxy** that sits between the Minecraft client
and the remote server:

```
Minecraft Client
      │  connects to 127.0.0.1:19135
      ▼
FreeConnect Proxy (ResourcePackProxy.kt)
      │  forwards all UDP traffic
      │  intercepts "Resource Pack Info" packets (0x06)
      │  prepends user's .mcpack files to the pack list
      ▼
Remote Bedrock Server (:19132)
```

**Steps to use custom packs on any server:**

1. Import your `.mcpack` file via the Resource Packs screen.
2. Enable the packs you want.
3. Tap **Start Proxy**.
4. In Minecraft, add a server at `127.0.0.1 : 19135`.
5. Connect — your packs load automatically.

> Both devices must be on the same Wi-Fi network.

---

## Permissions Required

| Permission | Reason |
|-----------|--------|
| `INTERNET` | UDP networking |
| `ACCESS_WIFI_STATE` | LAN broadcast |
| `CHANGE_WIFI_MULTICAST_STATE` | Multicast discovery |
| `FOREGROUND_SERVICE` | Keeps broadcast alive |
| `POST_NOTIFICATIONS` | Android 13+ foreground service notification |

---

## Troubleshooting

**Q: Minecraft doesn't see the broadcast**  
A: Ensure both devices are on the same Wi-Fi. Some routers block UDP broadcast — try a mobile hotspot.

**Q: The proxy isn't injecting packs**  
A: Enable at least one pack in the Resource Packs screen before starting the proxy.

**Q: Build fails with "AGP version" errors**  
A: Update Android Studio to Hedgehog (2023.1.1) or newer.
