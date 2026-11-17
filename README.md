# VideoEditing-

Native Android video editor built with Kotlin, Jetpack Compose and AndroidX Media3. Supports Android 10+ (API 29+) and uses the Android Photo Picker/Open Document picker rather than broad gallery permissions.

## Implemented

- Import and preview a video in a vertical 9:16 editor canvas.
- Export an H.264/MP4 at 1080×1920 using Media3 Transformer and save it to `Movies/VideoEditing` through `MediaStore`; export progress and completion feedback are shown in the app.
- Horizontal mirror and adjustable slight zoom.
- Adjustable warm color tint (default 30%).
- Custom PNG/JPG logo selection through Photo Picker. The image is copied into app-private `filesDir/custom-logo`, its path is saved in `SharedPreferences`, and it reloads on app startup. Drag the logo directly on the preview to reposition it; its coordinates are saved and used by export. A reset-position action is available.
- Optional original-audio muting (enabled by default) and replacement audio selection. Replacement audio is looped/cut to the video timeline by Media3 composition.
- Draggable/resizable subtitle cover box with expanded movement range, a corner resize grip and adjustable cover strength. Preview coordinates are shared with the 1080×1920 export.

## Important limitation

The subtitle box currently exports as a translucent frosted cover panel, not a true per-frame Gaussian blur of the underlying video. The strength control makes the cover more opaque to conceal text; it does not increase optical blur. Media3's stock blur effect is full-frame; a true moving, region-only blur requires a custom GPU video effect. Verify output on a device with the target codecs/media before production use; the build was compile-validated here, but no Android device/emulator was available for playback/export runtime testing.

## Build

Open the repository in Android Studio (AGP 8.7.3, Kotlin 2.0.21, JDK 17) and build the `app` debug variant, or run:

```bash
./gradlew assembleDebug
```

Debug APK path: `app/build/outputs/apk/debug/app-debug.apk`.
