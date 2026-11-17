# VideoEditing-

Native Android video editor built with Kotlin, Jetpack Compose and AndroidX Media3. Supports Android 10+ (API 29+) and uses the Android Photo Picker/Open Document picker rather than broad gallery permissions.

## Implemented

- Import and preview a video in a vertical 9:16 editor canvas that stays visible above the scrollable settings panel.
- Export an H.264/MP4 at portrait 720×1280 using Media3 Transformer, targeting 1.2 Mbps video and 96 kbps encoded audio, and save it to `Movies/VideoEditing` through `MediaStore`. This is roughly 9–10 MB/min when audio is present; actual size varies with the content and audio. Export progress and completion feedback are shown in the app.
- Horizontal mirror and adjustable slight zoom. Preview transforms apply to the video texture only, keeping player controls and time labels unmirrored.
- Adjustable warm color tint (default 30%); color, zoom and mirror preview updates apply while paused, without requiring playback.
- Custom PNG/JPG/GIF logo selection through Photo Picker. The image is copied into app-private `filesDir/custom-logo`, its path is saved in `SharedPreferences`, and it reloads on app startup. GIFs animate in the editor preview and are driven by the output timestamps during export, looping automatically for the full video duration. Drag the logo directly on the preview to reposition it; use the corner grip or size slider to resize it. Its saved position and size are used in export. A reset action is available.
- Preview plays the selected added audio in sync with the video, loops it to the video length, and follows video play/pause and seeks. Separate switches and volume sliders control the original video sound and added audio. If both are enabled, both are mixed in preview and in the exported video.
- Draggable/resizable subtitle cover box with expanded movement range, a corner resize grip and adjustable cover strength. Preview coordinates are shared with the 720×1280 export.

## Important limitation

The subtitle box currently exports as a translucent frosted cover panel, not a true per-frame Gaussian blur of the underlying video. The strength control makes the cover more opaque to conceal text; it does not increase optical blur. Media3's stock blur effect is full-frame; a true moving, region-only blur requires a custom GPU video effect. Verify output on a device with the target codecs/media before production use; the build was compile-validated here, but no Android device/emulator was available for playback/export runtime testing.

## Build

Open the repository in Android Studio (AGP 8.7.3, Kotlin 2.0.21, JDK 17) and build the `app` debug variant, or run:

```bash
./gradlew assembleDebug
```

Debug APK path: `app/build/outputs/apk/debug/app-debug.apk`.
