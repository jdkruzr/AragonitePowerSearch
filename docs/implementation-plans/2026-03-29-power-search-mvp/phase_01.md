# Power Search MVP Implementation Plan — Phase 1: Project Scaffolding

**Goal:** Create a multi-module Android app project that builds and runs on a BOOX e-ink tablet, requests storage permission, and shows an empty Compose scaffold.

**Architecture:** Single-activity Jetpack Compose app with two Gradle modules (`:app` for the Android app, `:fleece` for a pure Kotlin library) plus a composite build reference to the sibling AragoniteHWR library.

**Tech Stack:** Kotlin 2.0.21, AGP 8.7.0, Gradle 8.9, Jetpack Compose (BOM), Room (for later phases), Material 3, AragoniteHWR via `includeBuild`

**Scope:** Phase 1 of 6 from original design

**Codebase verified:** 2026-03-29. AragonitePowerSearch is pre-code (no Gradle files, no modules). AragoniteHWR verified at /home/jtd/AragoniteHWR with: AGP 8.7.0, Kotlin 2.0.21, Gradle 8.9, compileSdk 35, minSdk 29, Java 17, namespace `dev.aragonite.hwr`.

---

## Acceptance Criteria Coverage

This phase is infrastructure scaffolding. **Verifies: None** — verified operationally (app installs, requests permission, shows scaffold).

---

<!-- START_TASK_1 -->
### Task 1: Gradle wrapper and root build files

**Files:**
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradle/wrapper/gradle-wrapper.jar` (copied from AragoniteHWR)
- Create: `gradlew` (copied from AragoniteHWR)
- Create: `gradlew.bat` (copied from AragoniteHWR)
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`

**Step 1: Copy Gradle wrapper from AragoniteHWR**

```bash
cp -r /home/jtd/AragoniteHWR/gradle /home/jtd/AragonitePowerSearch/gradle
cp /home/jtd/AragoniteHWR/gradlew /home/jtd/AragonitePowerSearch/gradlew
cp /home/jtd/AragoniteHWR/gradlew.bat /home/jtd/AragonitePowerSearch/gradlew.bat
chmod +x /home/jtd/AragonitePowerSearch/gradlew
```

This gives us Gradle 8.9, matching AragoniteHWR exactly.

**Step 2: Create `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AragonitePowerSearch"
include(":app", ":fleece")
includeBuild("../AragoniteHWR") {
    dependencySubstitution {
        substitute(module("dev.aragonite:hwr")).using(project(":lib"))
    }
}
```

The `dependencySubstitution` block is required because AragoniteHWR's `:lib` module does not declare a `group` property. Without explicit substitution, Gradle cannot match the `dev.aragonite:hwr` coordinate to the local project.

**Step 3: Create root `build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application") version "8.7.0" apply false
    id("com.android.library") version "8.7.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
```

The `org.jetbrains.kotlin.jvm` plugin is declared here with `apply false` so the `:fleece` module can apply it. Without this declaration, the `:fleece` module build will fail to resolve the plugin.

**Step 4: Create `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
```

**Step 5: Verify Gradle resolves**

Run: `./gradlew --version`
Expected: Gradle 8.9 reported, no errors

**Step 6: Commit**

```bash
git add gradle/ gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties
git commit -m "chore: add Gradle wrapper and root build files"
```
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: fleece module (pure Kotlin library)

**Files:**
- Create: `fleece/build.gradle.kts`
- Create: `fleece/src/main/java/dev/aragonite/fleece/.gitkeep`

**Step 1: Create `fleece/build.gradle.kts`**

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}
```

Note: The fleece module uses the `kotlin("jvm")` plugin (NOT Android), since it has zero Android dependencies. The plugin version is inherited from the root `build.gradle.kts` via Gradle's plugin management. We use `org.jetbrains.kotlin.jvm` here (not `org.jetbrains.kotlin.android`) because this is a pure JVM library.

**Step 2: Create source directory with placeholder**

```bash
mkdir -p fleece/src/main/java/dev/aragonite/fleece
touch fleece/src/main/java/dev/aragonite/fleece/.gitkeep
mkdir -p fleece/src/test/java/dev/aragonite/fleece
touch fleece/src/test/java/dev/aragonite/fleece/.gitkeep
```

**Step 3: Verify module resolves**

Run: `./gradlew :fleece:build`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add fleece/
git commit -m "chore: add fleece module (pure Kotlin library)"
```
<!-- END_TASK_2 -->

<!-- START_TASK_3 -->
### Task 3: app module build config

**Files:**
- Create: `app/build.gradle.kts`

**Step 1: Create `app/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.aragonite.powersearch"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.aragonite.powersearch"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Compose BOM — manages all Compose artifact versions
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Room + FTS
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Coroutines (matches AragoniteHWR)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Sibling modules
    implementation(project(":fleece"))
    implementation("dev.aragonite:hwr")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
}
```

The `dev.aragonite:hwr` dependency resolves via `includeBuild("../AragoniteHWR")` in settings.gradle.kts. Gradle's composite build substitution maps this to the local AragoniteHWR `:lib` module.

**Step 2: Verify Gradle sync resolves**

Run: `./gradlew :app:dependencies --configuration releaseRuntimeClasspath`
Expected: Dependencies resolve, including AragoniteHWR via composite build. No unresolved artifacts.

**Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "chore: add app module build config with Compose, Room, AragoniteHWR"
```
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: AndroidManifest and resources

**Files:**
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`

**Step 1: Create `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Required to read /sdcard/.ksync/ on Android 11+ -->
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE"
        tools:ignore="ScopedStorage" />

    <application
        android:allowBackup="false"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.AragonitePowerSearch">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.AragonitePowerSearch">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

**Step 2: Create `app/src/main/res/values/strings.xml`**

```xml
<resources>
    <string name="app_name">Power Search</string>
</resources>
```

**Step 3: Create `app/src/main/res/values/themes.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.AragonitePowerSearch" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

This is a minimal Android theme. Compose handles actual theming via Material 3; this theme just prevents the default ActionBar.

**Step 4: Create source directories**

```bash
mkdir -p app/src/main/java/dev/aragonite/powersearch
```

Note: Using `java/` (not `kotlin/`) for Kotlin source directories matches the AragoniteHWR sibling project convention (`AragoniteHWR/lib/src/main/java/dev/aragonite/hwr/`).

**Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/ app/src/main/java/
git commit -m "chore: add AndroidManifest, strings, and theme"
```
<!-- END_TASK_4 -->

<!-- START_TASK_5 -->
### Task 5: MainActivity with storage permission flow

**Files:**
- Create: `app/src/main/java/dev/aragonite/powersearch/MainActivity.kt`

**Step 1: Create `app/src/main/java/dev/aragonite/powersearch/MainActivity.kt`**

```kotlin
package dev.aragonite.powersearch

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

    private var hasStoragePermission by mutableStateOf(false)

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasStoragePermission = Environment.isExternalStorageManager()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hasStoragePermission = Environment.isExternalStorageManager()
        setContent {
            PowerSearchApp(
                hasStoragePermission = hasStoragePermission,
                onRequestPermission = ::requestStoragePermission
            )
        }
    }

    override fun onResume() {
        super.onResume()
        hasStoragePermission = Environment.isExternalStorageManager()
    }

    private fun requestStoragePermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )
        storagePermissionLauncher.launch(intent)
    }
}

@Composable
fun PowerSearchApp(hasStoragePermission: Boolean, onRequestPermission: () -> Unit) {
    MaterialTheme {
        Scaffold { padding ->
            if (hasStoragePermission) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Power Search", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Ready. Search UI coming in Phase 6.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Storage permission required",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        "Power Search needs access to read handwriting data from .ksync files.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                    )
                    Button(onClick = onRequestPermission) {
                        Text("Grant Permission")
                    }
                }
            }
        }
    }
}
```

**MANAGE_EXTERNAL_STORAGE note:** This permission requires the user to navigate to a Settings page (not a standard runtime dialog). The `storagePermissionLauncher` opens that Settings page and re-checks on return. `onResume` also re-checks in case the user granted it manually.

**Step 2: Build the APK**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL, APK at `app/build/outputs/apk/debug/app-debug.apk`

**Step 3: Commit**

```bash
git add app/src/main/java/dev/aragonite/powersearch/MainActivity.kt
git commit -m "feat: add MainActivity with storage permission flow and Compose scaffold"
```
<!-- END_TASK_5 -->

<!-- START_TASK_6 -->
### Task 6: Verify end-to-end on device

This is a manual verification task. No code changes.

**Step 1: Install on device**

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Expected: `Success`

**Step 2: Launch app**

```bash
adb shell am start -n dev.aragonite.powersearch/.MainActivity
```

Expected: App launches, shows either:
- Permission request screen (if MANAGE_EXTERNAL_STORAGE not yet granted)
- "Power Search — Ready" scaffold (if permission already granted)

**Step 3: Test permission flow**

1. If permission screen shows: tap "Grant Permission"
2. System Settings page opens for All Files Access
3. Toggle on, press back
4. App shows "Ready" scaffold

**Step 4: Verify no crashes in logcat**

```bash
adb logcat -s ActivityManager:I *:E | head -50
```

Expected: No crashes or exceptions related to `dev.aragonite.powersearch`

**Done when:** App installs, requests storage permission, shows empty Compose scaffold. Phase 1 complete.
<!-- END_TASK_6 -->
