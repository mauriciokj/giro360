import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

abstract interface class Giro360CaptureBackend {
  Stream<Giro360CaptureStatus> get events;

  Future<bool> isSupported();

  Future<void> startCapture({
    required Directory sessionDirectory,
    int binCount,
    int requiredLaps,
  });

  Future<Giro360CaptureStatus> status();

  Future<void> cancelCapture();
}

class Giro360NativeCaptureService implements Giro360CaptureBackend {
  static const _methodChannel = MethodChannel('giro360_capture/methods');
  static const _eventChannel = EventChannel('giro360_capture/events');

  Stream<Giro360CaptureStatus>? _events;

  @override
  Stream<Giro360CaptureStatus> get events =>
      _events ??= _eventChannel.receiveBroadcastStream().map((value) {
        if (value is! Map<Object?, Object?>) {
          throw const FormatException('Evento de captura ARKit inválido.');
        }
        return Giro360CaptureStatus.fromMap(value);
      });

  @override
  Future<bool> isSupported() async {
    if (!Platform.isIOS) {
      return false;
    }
    return await _methodChannel.invokeMethod<bool>('isSupported') ?? false;
  }

  @override
  Future<void> startCapture({
    required Directory sessionDirectory,
    int binCount = 30,
    int requiredLaps = 2,
  }) {
    return _methodChannel.invokeMethod<void>('startCapture', {
      'directoryPath': sessionDirectory.path,
      'binCount': binCount,
      'requiredLaps': requiredLaps,
    });
  }

  @override
  Future<Giro360CaptureStatus> status() async {
    final value = await _methodChannel.invokeMethod<Object?>('status');
    if (value is! Map<Object?, Object?>) {
      throw const FormatException('Status ARKit inválido.');
    }
    return Giro360CaptureStatus.fromMap(value);
  }

  @override
  Future<void> cancelCapture() {
    return _methodChannel.invokeMethod<void>('cancelCapture');
  }
}

class Giro360CapturePreview extends StatelessWidget {
  const Giro360CapturePreview({super.key});

  @override
  Widget build(BuildContext context) {
    if (!Platform.isIOS) {
      return const ColoredBox(color: Color(0xff000000));
    }
    return const UiKitView(
      viewType: 'giro360_capture/preview',
      layoutDirection: TextDirection.ltr,
      creationParamsCodec: StandardMessageCodec(),
    );
  }
}

class Giro360CaptureStatus {
  const Giro360CaptureStatus({
    required this.running,
    required this.finishing,
    required this.complete,
    required this.failed,
    required this.message,
    required this.trackingState,
    required this.direction,
    required this.movingWrongDirection,
    required this.progress,
    required this.progressDegrees,
    required this.completedLaps,
    required this.requiredLaps,
    required this.binCount,
    required this.selectedCount,
    required this.selectedLap,
    required this.lapCandidateCounts,
    required this.missingBins,
    required this.currentPitchDegrees,
    required this.currentRollDegrees,
    required this.currentAngularSpeed,
    required this.currentTranslationMeters,
    required this.maxTranslationMeters,
    required this.processedFrameCount,
    required this.encodedCandidateCount,
    required this.recordedVideoFrameCount,
    required this.droppedVideoFrameCount,
    required this.videoPath,
    required this.videoTimelinePath,
    required this.rejectedTrackingFrameCount,
    required this.rejectedTranslationFrameCount,
    required this.frames,
  });

  factory Giro360CaptureStatus.fromMap(Map<Object?, Object?> map) {
    return Giro360CaptureStatus(
      running: _bool(map, 'running'),
      finishing: _bool(map, 'finishing'),
      complete: _bool(map, 'complete'),
      failed: _bool(map, 'failed'),
      message: _string(map, 'message'),
      trackingState: _string(map, 'trackingState'),
      direction: _string(map, 'direction'),
      movingWrongDirection: _bool(map, 'movingWrongDirection'),
      progress: _double(map, 'progress').clamp(0, 1),
      progressDegrees: _double(map, 'progressDegrees'),
      completedLaps: _int(map, 'completedLaps'),
      requiredLaps: _int(map, 'requiredLaps'),
      binCount: _int(map, 'binCount'),
      selectedCount: _int(map, 'selectedCount'),
      selectedLap: _int(map, 'selectedLap'),
      lapCandidateCounts: _intList(map['lapCandidateCounts']),
      missingBins: _intList(map['missingBins']),
      currentPitchDegrees: _double(map, 'currentPitchDegrees'),
      currentRollDegrees: _double(map, 'currentRollDegrees'),
      currentAngularSpeed: _double(map, 'currentAngularSpeed'),
      currentTranslationMeters: _double(map, 'currentTranslationMeters'),
      maxTranslationMeters: _double(map, 'maxTranslationMeters'),
      processedFrameCount: _int(map, 'processedFrameCount'),
      encodedCandidateCount: _int(map, 'encodedCandidateCount'),
      recordedVideoFrameCount: _int(map, 'recordedVideoFrameCount'),
      droppedVideoFrameCount: _int(map, 'droppedVideoFrameCount'),
      videoPath: _string(map, 'videoPath'),
      videoTimelinePath: _string(map, 'videoTimelinePath'),
      rejectedTrackingFrameCount: _int(map, 'rejectedTrackingFrameCount'),
      rejectedTranslationFrameCount: _int(map, 'rejectedTranslationFrameCount'),
      frames: _objectList(map['frames'])
          .whereType<Map<Object?, Object?>>()
          .map(Giro360VideoFrame.fromMap)
          .toList(growable: false),
    );
  }

  final bool running;
  final bool finishing;
  final bool complete;
  final bool failed;
  final String message;
  final String trackingState;
  final String direction;
  final bool movingWrongDirection;
  final double progress;
  final double progressDegrees;
  final int completedLaps;
  final int requiredLaps;
  final int binCount;
  final int selectedCount;
  final int selectedLap;
  final List<int> lapCandidateCounts;
  final List<int> missingBins;
  final double currentPitchDegrees;
  final double currentRollDegrees;
  final double currentAngularSpeed;
  final double currentTranslationMeters;
  final double maxTranslationMeters;
  final int processedFrameCount;
  final int encodedCandidateCount;
  final int recordedVideoFrameCount;
  final int droppedVideoFrameCount;
  final String videoPath;
  final String videoTimelinePath;
  final int rejectedTrackingFrameCount;
  final int rejectedTranslationFrameCount;
  final List<Giro360VideoFrame> frames;

  int get activeLap {
    if (complete) {
      return requiredLaps;
    }
    return (completedLaps + 1).clamp(1, requiredLaps);
  }
}

class Giro360VideoFrame {
  const Giro360VideoFrame({
    required this.binIndex,
    required this.lapIndex,
    required this.filePath,
    required this.targetYawRadians,
    required this.relativeYawRadians,
    required this.pitchRadians,
    required this.rollRadians,
    required this.translationMeters,
    required this.qualityScore,
    required this.sharpnessScore,
    required this.angularSpeedRadiansPerSecond,
    required this.centerErrorRadians,
    required this.trackingState,
    required this.capturedAt,
    required this.frameTimestampSeconds,
    required this.videoTimeSeconds,
    required this.cameraIntrinsics,
    required this.cameraTransform,
  });

  factory Giro360VideoFrame.fromMap(Map<Object?, Object?> map) {
    return Giro360VideoFrame(
      binIndex: _int(map, 'binIndex'),
      lapIndex: _int(map, 'lapIndex'),
      filePath: _string(map, 'filePath'),
      targetYawRadians: _double(map, 'targetYawRadians'),
      relativeYawRadians: _double(map, 'relativeYawRadians'),
      pitchRadians: _double(map, 'pitchRadians'),
      rollRadians: _double(map, 'rollRadians'),
      translationMeters: _double(map, 'translationMeters'),
      qualityScore: _double(map, 'qualityScore'),
      sharpnessScore: _double(map, 'sharpnessScore'),
      angularSpeedRadiansPerSecond:
          _double(map, 'angularSpeedRadiansPerSecond'),
      centerErrorRadians: _double(map, 'centerErrorRadians'),
      trackingState: _string(map, 'trackingState'),
      capturedAt: DateTime.tryParse(_string(map, 'capturedAt')) ??
          DateTime.fromMillisecondsSinceEpoch(0, isUtc: true),
      frameTimestampSeconds: _double(map, 'frameTimestampSeconds'),
      videoTimeSeconds: _double(map, 'videoTimeSeconds'),
      cameraIntrinsics: _doubleList(map['cameraIntrinsics']),
      cameraTransform: _doubleList(map['cameraTransform']),
    );
  }

  final int binIndex;
  final int lapIndex;
  final String filePath;
  final double targetYawRadians;
  final double relativeYawRadians;
  final double pitchRadians;
  final double rollRadians;
  final double translationMeters;
  final double qualityScore;
  final double sharpnessScore;
  final double angularSpeedRadiansPerSecond;
  final double centerErrorRadians;
  final String trackingState;
  final DateTime capturedAt;
  final double frameTimestampSeconds;
  final double videoTimeSeconds;
  final List<double> cameraIntrinsics;
  final List<double> cameraTransform;
}

bool _bool(Map<Object?, Object?> map, String key) => map[key] == true;

double _double(Map<Object?, Object?> map, String key) =>
    (map[key] as num?)?.toDouble() ?? 0;

int _int(Map<Object?, Object?> map, String key) =>
    (map[key] as num?)?.toInt() ?? 0;

String _string(Map<Object?, Object?> map, String key) =>
    map[key]?.toString() ?? '';

List<double> _doubleList(Object? value) => _objectList(value)
    .whereType<num>()
    .map((item) => item.toDouble())
    .toList(growable: false);

List<int> _intList(Object? value) => _objectList(value)
    .whereType<num>()
    .map((item) => item.toInt())
    .toList(growable: false);

List<Object?> _objectList(Object? value) {
  if (value is! List) {
    return const <Object?>[];
  }
  return value.cast<Object?>();
}
