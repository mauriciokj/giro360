import 'package:flutter_test/flutter_test.dart';
import 'package:giro360_capture/giro360_capture.dart';

void main() {
  test('parses capture progress and selected video frame metadata', () {
    final status = Giro360CaptureStatus.fromMap(_statusMap(complete: true));

    expect(status.complete, isTrue);
    expect(status.activeLap, 2);
    expect(status.frames.single.binIndex, 0);
    expect(status.frames.single.qualityScore, 72.5);
    expect(status.videoPath, '/tmp/giro360_capture.mp4');
  });

  test('keeps the validated video refinement native contract stable', () {
    expect(GuidedAlignmentMode.videoRefine.nativeValue, 4);
    expect(GuidedAlignmentMode.videoRefine.label, 'ORB vídeo coerente');
  });
}

Map<Object?, Object?> _statusMap({required bool complete}) => {
      'running': !complete,
      'complete': complete,
      'message': complete ? 'ok' : 'capturing',
      'trackingState': 'normal',
      'direction': 'right',
      'progress': complete ? 1.0 : 0.5,
      'progressDegrees': complete ? 720.0 : 360.0,
      'completedLaps': complete ? 2 : 1,
      'requiredLaps': 2,
      'binCount': 30,
      'selectedCount': 1,
      'selectedLap': 2,
      'lapCandidateCounts': <Object?>[30, 30],
      'videoPath': '/tmp/giro360_capture.mp4',
      'videoTimelinePath': '/tmp/giro360_video_timeline.json',
      'frames': <Object?>[
        <Object?, Object?>{
          'binIndex': 0,
          'lapIndex': 1,
          'filePath': '/tmp/video_000.jpg',
          'targetYawRadians': 0.0,
          'relativeYawRadians': 0.01,
          'pitchRadians': -0.02,
          'rollRadians': 0.03,
          'translationMeters': 0.04,
          'qualityScore': 72.5,
          'sharpnessScore': 31.2,
          'angularSpeedRadiansPerSecond': 0.2,
          'centerErrorRadians': 0.01,
          'trackingState': 'normal',
          'capturedAt': '2026-07-21T01:02:03.000Z',
          'frameTimestampSeconds': 42.0,
          'videoTimeSeconds': 12.5,
          'cameraIntrinsics': <Object?>[1.0, 0.0, 2.0],
          'cameraTransform': <Object?>[1.0, 0.0, 0.0, 1.0],
        },
      ],
    };
