## 0.0.4

- Automatically profile the saved video and choose coherent keyframes after capture; default to 60 images.
- Preserve all normally tracked recorded sectors, including frames farther from the session origin, and score relative camera movement and the circular seam.
- Stream AVFoundation decoding and thumbnail sharpness analysis without retaining full-resolution video frames in memory.
- Save selection timings, per-lap scores, exact encoded timestamps and source camera metadata.
- Cancel processing safely and isolate callbacks between capture attempts; preserve the original video on failure.
- Add `selectRecordedVideo` for SDK recovery and reproducible device validation, always writing to a fresh output directory.

## 0.0.3

- Ignore replayed progress, frames and terminal events from earlier capture directories.
- iOS event subscriptions no longer replay a finished or failed capture on listen.
- Preserve failures belonging to the new capture, including before the start method replies.
- Use a fresh session directory for each run to keep results and event ownership separate.

## 0.0.2

- Select only frames successfully written to the video and extract at the exact recorded timestamp; verify the returned timestamp before associating pixels with camera pose.
- Wait for normal ARKit tracking before establishing the capture origin.
- Preserve intrinsics, transforms, image dimensions and recording status in the video timeline.
- Add lossless JSON-compatible capture/frame snapshots for consumer session recovery.
- Validate encoded frame identities and timestamps with a macOS AVFoundation integration check (`swift tool/verify_video_timestamps.swift`).

## 0.0.1

- Extrai captura ARKit por video para um plugin Flutter reutilizavel.
- Publica progresso por `EventChannel` e API tipada em Dart.
- Empacota o motor OpenCV e o modo de alinhamento `videoRefine` no pod iOS.
- Inclui controlador completo, preview opcional, testes e app de exemplo.
