import 'dart:async';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:giro360_capture/giro360_capture.dart';

void main() {
  test('turns a completed native capture into a stitched result', () async {
    final directory = await Directory.systemTemp.createTemp('giro360_test_');
    addTearDown(() => directory.delete(recursive: true));
    final frame = File('${directory.path}/video_000.jpg')
      ..writeAsBytesSync([1]);
    final backend = _FakeCaptureBackend();
    final stitcher = _FakeStitchBackend();
    final controller = Giro360CaptureController(
      captureBackend: backend,
      stitchBackend: stitcher,
    );
    addTearDown(controller.dispose);
    final stages = <Giro360CaptureStage>[];
    final subscription = controller.events.listen(
      (event) => stages.add(event.stage),
    );
    addTearDown(subscription.cancel);

    final captureStarted = controller.events.firstWhere(
      (event) => event.stage == Giro360CaptureStage.capturing,
    );
    final future = controller.start(sessionDirectory: directory);
    await captureStarted;
    backend.emit(_completeStatus(frame.path, directory.path));
    final result = await future;

    expect(result.panorama.file.existsSync(), isTrue);
    expect(result.selectedFrames.single.filePath, frame.path);
    expect(stitcher.alignmentMode, GuidedAlignmentMode.videoRefine);
    expect(stitcher.telemetry.single.actualYaw, 0.01);
    expect(
        stages,
        containsAllInOrder([
          Giro360CaptureStage.capturing,
          Giro360CaptureStage.stitching,
          Giro360CaptureStage.completed,
        ]));
  });

  test('rejects a second capture while one is running', () async {
    final directory = await Directory.systemTemp.createTemp('giro360_test_');
    addTearDown(() => directory.delete(recursive: true));
    final backend = _FakeCaptureBackend();
    final controller = Giro360CaptureController(captureBackend: backend);
    addTearDown(controller.dispose);

    final firstRun = controller.start(sessionDirectory: directory);
    await expectLater(
      controller.start(sessionDirectory: directory),
      throwsStateError,
    );
    final firstRunExpectation = expectLater(
      firstRun,
      throwsA(isA<Giro360CaptureCancelledException>()),
    );
    await controller.cancel();
    await firstRunExpectation;
  });
}

class _FakeCaptureBackend implements Giro360CaptureBackend {
  final _events = StreamController<Giro360CaptureStatus>.broadcast();

  void emit(Giro360CaptureStatus status) => _events.add(status);

  @override
  Stream<Giro360CaptureStatus> get events => _events.stream;

  @override
  Future<void> cancelCapture() async {}

  @override
  Future<bool> isSupported() async => true;

  @override
  Future<void> startCapture({
    required Directory sessionDirectory,
    int binCount = 30,
    int requiredLaps = 2,
  }) async {}

  @override
  Future<Giro360CaptureStatus> status() async => throw UnimplementedError();
}

class _FakeStitchBackend implements Giro360StitchBackend {
  GuidedAlignmentMode? alignmentMode;
  List<Giro360FrameTelemetry> telemetry = const [];

  @override
  Future<PanoramaStitchResult> stitch({
    required List<File> inputImages,
    required File outputFile,
    GuidedFallbackFillMode fallbackFillMode = GuidedFallbackFillMode.blackBands,
    GuidedAlignmentMode alignmentMode = GuidedAlignmentMode.horizontalRefine,
    List<Giro360FrameTelemetry>? frameTelemetry,
  }) async {
    this.alignmentMode = alignmentMode;
    telemetry = frameTelemetry ?? const [];
    outputFile.writeAsBytesSync([1, 2, 3]);
    return PanoramaStitchResult(
      file: outputFile,
      usedGuidedFallback: true,
    );
  }
}

Giro360CaptureStatus _completeStatus(String framePath, String directory) {
  return Giro360CaptureStatus.fromMap({
    'complete': true,
    'message': 'complete',
    'trackingState': 'normal',
    'direction': 'right',
    'progress': 1.0,
    'progressDegrees': 720.0,
    'completedLaps': 2,
    'requiredLaps': 2,
    'binCount': 30,
    'selectedCount': 1,
    'selectedLap': 2,
    'videoPath': '$directory/giro360_capture.mp4',
    'videoTimelinePath': '$directory/giro360_video_timeline.json',
    'frames': <Object?>[
      <Object?, Object?>{
        'binIndex': 0,
        'lapIndex': 1,
        'filePath': framePath,
        'targetYawRadians': 0.0,
        'relativeYawRadians': 0.01,
        'pitchRadians': -0.02,
        'rollRadians': 0.03,
        'translationMeters': 0.01,
        'qualityScore': 72.5,
        'sharpnessScore': 31.2,
        'angularSpeedRadiansPerSecond': 0.2,
        'centerErrorRadians': 0.01,
        'trackingState': 'normal',
        'capturedAt': '2026-07-21T01:02:03.000Z',
      },
    ],
  });
}
