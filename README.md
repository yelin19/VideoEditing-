# VideoEditing-

Native Android video editor built with Kotlin, Jetpack Compose and AndroidX Media3. Supports Android 10+ (API 29+) and uses the Android Photo Picker/Open Document picker rather than broad gallery permissions.

## Implemented

- Import and preview a video in a vertical 9:16 editor canvas that stays visible above the scrollable settings panel.
- Export an H.264/MP4 at portrait 720×1280 using Media3 Transformer, targeting 1.2 Mbps video and 96 kbps encoded audio, and save it to `Movies/VideoEditing` through `MediaStore`. This is roughly 9–10 MB/min when audio is present; actual size varies with the content and audio. Export progress and completion feedback are shown in the app.
- Horizontal mirror and adjustable slight zoom. Export applies zoom and a center crop before fitting into the 720×1280 frame, so the exported image has the same cropped-in appearance as the preview instead of only showing black/empty edges. Preview transforms apply to the video texture only, keeping player controls and time labels unmirrored.
- Adjustable warm color tint (default 30%); color, zoom and mirror preview updates apply while paused, without requiring playback.
- Custom PNG/JPG/GIF logo selection through Photo Picker with an in-app crop dialog: drag the selection to move it and its corner handle to resize, then save the cropped selection permanently as PNG. GIFs can be kept animated with the “Keep animated” option; cropping a GIF saves its visible frame as a still PNG. Logo files are stored in app-private `filesDir/custom-logo`, their path is saved in `SharedPreferences`, and they reload on app startup. Animated GIFs loop for the full video duration during export. Drag the logo directly on the preview to reposition it; use the corner grip or size slider to resize it. A reset action is available.
- Preview plays the selected added audio in sync with the video, loops it to the video length, and follows video play/pause and seeks. Separate switches and volume sliders control the original video sound and added audio. If both are enabled, both are mixed in preview and in the exported video.
- Draggable/resizable subtitle cover box with expanded movement range, a corner resize grip and adjustable cover strength. Preview coordinates are shared with the 720×1280 export.
- Selectable colored frame border and editable scrolling caption displayed at the bottom of the preview and rendered video.
- Myanmar subtitle tools: request a post-install download of Nara SAR's CPU INT8 acoustic model (about 366 MB) and bilingual character language model (about 84 MB), then transcribe the imported video's audio locally. Model files are not bundled in the APK and are checksum-verified in app-private storage. The decoder applies the released CTC beam search, character LM and Burmese syllable constraints; Latin-script output is filtered so generated subtitles focus on Myanmar text.
- Generated captions are divided into approximate 2-second subtitle cards and appear in preview and MP4 export. Nara SAR does not return word-level timestamps, so the app estimates which words belong in each 2-second interval; review transcript and timing before publishing. Existing `.srt` subtitles with their own timestamps can also be imported, previewed and rendered accurately.

## Standalone audio transcription

For a separate audio file, download the compact INT8 inference package and pass the local audio path to the official runner. The package includes the acoustic model, matching tokenizer/config, language model, decoder, inference script and license/attribution files. The redundant 1.3 GB FP32 weight/ONNX files are not needed for INT8 inference.

```bash
python3 tools/nara_sar/download_model.py
python3 -m pip install -r tools/nara_sar/model/requirements.txt
python3 tools/nara_sar/run_transcribe.py /path/to/your-audio.wav
```

The user did not attach an audio file or provide an audio link in this request, so no real recording was transcribed here. The command above is ready to run once the audio file is available.

## Important limitation

The subtitle box currently exports as a translucent frosted cover panel, not a true per-frame Gaussian blur of the underlying video. The strength control makes the cover more opaque to conceal text; it does not increase optical blur. Media3's stock blur effect is full-frame; a true moving, region-only blur requires a custom GPU video effect. Verify output on a device with the target codecs/media before production use; the build was compile-validated here, but no Android device/emulator was available for playback/export runtime testing.

## Build

Open the repository in Android Studio (AGP 8.7.3, Kotlin 2.0.21, JDK 17) and build the `app` debug variant, or run:

```bash
./gradlew assembleDebug
```

The build creates separate `arm64-v8a` and `armeabi-v7a` debug APKs under `app/build/outputs/apk/debug/`, so each APK includes only its matching ONNX Runtime native library. Nara SAR model weights are not part of either APK; users download them from the editor after installation.
