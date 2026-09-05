import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:giro360_capture/giro360_capture.dart';

void main() {
  test('capture snapshots retain geometry through JSON and native parsing', () {
    final snapshot = Giro360CaptureStatus.fromMap({
      'running': true,
      'binCount': 30,
      'requiredLaps': 2,
      'selectedCount': 1,
      'selectedLap': 1,
      'missingBins': [1, 2],
      'recordedVideoFrameCount': 120,
      'droppedVideoFrameCount': 3,
      'videoPath': 'capture.mp4',
      'videoTimelinePath': 'timeline.json',
      'frames': [
        {
          'binIndex': 0,
          'filePath': 'video_000.jpg',
          'relativeYawRadians': 0.1,
          'pitchRadians': 0.02,
          'rollRadians': 0.03,
          'frameTimestampSeconds': 42.12,
          'videoTimeSeconds': 1.25,
          'capturedAt': '2026-09-05T10:00:00Z',
          'cameraIntrinsics': List.generate(9, (i) => i * 1.1),
          'cameraTransform': List.generate(16, (i) => i * 0.2),
        }
      ],
    });
    final restored = Giro360CaptureStatus.fromMap(
        jsonDecode(jsonEncode(snapshot.toMap())) as Map);
    expect(restored.frames.single.cameraIntrinsics,
        snapshot.frames.single.cameraIntrinsics);
    expect(restored.frames.single.cameraTransform,
        snapshot.frames.single.cameraTransform);
    expect(restored.frames.single.frameTimestampSeconds, 42.12);
    expect(restored.frames.single.capturedAt, DateTime.utc(2026, 9, 5, 10));
    expect(restored.missingBins, [1, 2]);
    expect(restored.droppedVideoFrameCount, 3);
    expect(restored.toMap(), snapshot.toMap());
  });
}
