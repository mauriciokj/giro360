import 'dart:async';
import 'dart:io';

import 'arkit_capture_service.dart';
import 'panorama_stitcher.dart';
import 'stitch_frame_telemetry.dart';

enum Giro360CaptureStage {
  capturing,
  finalizing,
  stitching,
  completed,
  cancelled,
  failed,
}

class Giro360CaptureConfig {
  const Giro360CaptureConfig({
    this.binCount = 30,
    this.requiredLaps = 2,
    this.outputFileName = 'giro360_panorama.jpg',
    this.fallbackFillMode = GuidedFallbackFillMode.blackBands,
    this.alignmentMode = GuidedAlignmentMode.videoRefine,
  })  : assert(binCount >= 24 && binCount <= 90),
        assert(requiredLaps >= 1 && requiredLaps <= 3);

  final int binCount;
  final int requiredLaps;
  final String outputFileName;
  final GuidedFallbackFillMode fallbackFillMode;
  final GuidedAlignmentMode alignmentMode;
}

class Giro360CaptureEvent {
  const Giro360CaptureEvent({
    required this.stage,
    required this.message,
    this.status,
    this.result,
    this.error,
  });

  final Giro360CaptureStage stage;
  final String message;
  final Giro360CaptureStatus? status;
  final Giro360CaptureResult? result;
  final Object? error;
}

class Giro360CaptureResult {
  const Giro360CaptureResult({
    required this.captureStatus,
    required this.panorama,
    required this.videoFile,
    required this.timelineFile,
    required this.selectedFrames,
    required this.stitchElapsed,
  });

  final Giro360CaptureStatus captureStatus;
  final PanoramaStitchResult panorama;
  final File videoFile;
  final File timelineFile;
  final List<Giro360VideoFrame> selectedFrames;
  final Duration stitchElapsed;
}

class Giro360CaptureController {
  Giro360CaptureController({
    Giro360CaptureBackend? captureBackend,
    Giro360StitchBackend? stitchBackend,
  })  : _captureBackend = captureBackend ?? Giro360NativeCaptureService(),
        _stitchBackend = stitchBackend ?? PanoramaStitcher();

  final Giro360CaptureBackend _captureBackend;
  final Giro360StitchBackend _stitchBackend;
  final _events = StreamController<Giro360CaptureEvent>.broadcast();

  StreamSubscription<Giro360CaptureStatus>? _statusSubscription;
  Completer<Giro360CaptureResult>? _runCompleter;
  Directory? _sessionDirectory;
  Giro360CaptureConfig? _config;
  bool _processingResult = false;
  bool _cancelRequested = false;
  bool _disposed = false;

  Stream<Giro360CaptureEvent> get events => _events.stream;

  bool get isRunning => _runCompleter != null && !_runCompleter!.isCompleted;

  Future<bool> isSupported() => _captureBackend.isSupported();

  Future<Giro360CaptureResult> start({
    required Directory sessionDirectory,
    Giro360CaptureConfig config = const Giro360CaptureConfig(),
  }) {
    if (_disposed) {
      return Future<Giro360CaptureResult>.error(
        StateError('O controlador Giro360 já foi descartado.'),
        StackTrace.current,
      );
    }
    if (isRunning) {
      return Future<Giro360CaptureResult>.error(
        StateError('Já existe uma captura Giro360 em andamento.'),
        StackTrace.current,
      );
    }

    final runCompleter = Completer<Giro360CaptureResult>();
    _runCompleter = runCompleter;
    _cancelRequested = false;
    unawaited(
      _beginCapture(
        sessionDirectory: sessionDirectory,
        config: config,
        runCompleter: runCompleter,
      ),
    );
    return runCompleter.future;
  }

  Future<void> _beginCapture({
    required Directory sessionDirectory,
    required Giro360CaptureConfig config,
    required Completer<Giro360CaptureResult> runCompleter,
  }) async {
    try {
      await sessionDirectory.create(recursive: true);
      if (_cancelRequested ||
          runCompleter.isCompleted ||
          !identical(_runCompleter, runCompleter)) {
        return;
      }
      _sessionDirectory = sessionDirectory;
      _config = config;
      _processingResult = false;
      await _statusSubscription?.cancel();
      _statusSubscription = _captureBackend.events.listen(
        _handleStatus,
        onError: _handleBackendError,
      );
      await _captureBackend.startCapture(
        sessionDirectory: sessionDirectory,
        binCount: config.binCount,
        requiredLaps: config.requiredLaps,
      );
      if (_cancelRequested ||
          runCompleter.isCompleted ||
          !identical(_runCompleter, runCompleter)) {
        await _captureBackend.cancelCapture();
        return;
      }
      _emit(
        const Giro360CaptureEvent(
          stage: Giro360CaptureStage.capturing,
          message: 'Captura iniciada.',
        ),
      );
    } catch (error, stackTrace) {
      await _completeError(
        error,
        stackTrace,
        runCompleter: runCompleter,
      );
    }
  }

  Future<void> cancel() async {
    if (!isRunning) {
      return;
    }
    _cancelRequested = true;
    await _captureBackend.cancelCapture();
    final error = const Giro360CaptureCancelledException();
    _emit(
      Giro360CaptureEvent(
        stage: Giro360CaptureStage.cancelled,
        message: 'Captura cancelada.',
        error: error,
      ),
    );
    await _completeError(error, StackTrace.current);
  }

  Future<void> dispose() async {
    if (_disposed) {
      return;
    }
    if (isRunning) {
      await cancel();
    }
    _disposed = true;
    await _statusSubscription?.cancel();
    await _events.close();
  }

  void _handleStatus(Giro360CaptureStatus status) {
    if (!isRunning || _cancelRequested) {
      return;
    }
    if (status.failed) {
      final error = Giro360CaptureException(status.message);
      _emit(
        Giro360CaptureEvent(
          stage: Giro360CaptureStage.failed,
          message: status.message,
          status: status,
          error: error,
        ),
      );
      unawaited(_completeError(error, StackTrace.current));
      return;
    }
    if (status.complete) {
      if (!_processingResult) {
        _processingResult = true;
        unawaited(_stitch(status));
      }
      return;
    }

    _emit(
      Giro360CaptureEvent(
        stage: status.finishing
            ? Giro360CaptureStage.finalizing
            : Giro360CaptureStage.capturing,
        message: status.message,
        status: status,
      ),
    );
  }

  Future<void> _stitch(Giro360CaptureStatus status) async {
    final runCompleter = _runCompleter!;
    final directory = _sessionDirectory!;
    final config = _config!;
    try {
      final selectedFrames = status.frames.toList(growable: false)
        ..sort((a, b) => a.binIndex.compareTo(b.binIndex));
      final imageFiles = selectedFrames
          .map((frame) => File(frame.filePath))
          .toList(growable: false);
      final missingFiles =
          imageFiles.where((file) => !file.existsSync()).toList();
      if (missingFiles.isNotEmpty) {
        throw Giro360CaptureException(
          '${missingFiles.length} frames selecionados não foram encontrados.',
        );
      }

      _emit(
        Giro360CaptureEvent(
          stage: Giro360CaptureStage.stitching,
          message: 'Costurando ${imageFiles.length} frames...',
          status: status,
        ),
      );
      final stopwatch = Stopwatch()..start();
      final panorama = await _stitchBackend.stitch(
        inputImages: imageFiles,
        outputFile: File('${directory.path}/${config.outputFileName}'),
        fallbackFillMode: config.fallbackFillMode,
        alignmentMode: config.alignmentMode,
        frameTelemetry: selectedFrames
            .map(
              (frame) => Giro360FrameTelemetryData(
                actualYaw: frame.relativeYawRadians,
                actualPitch: frame.pitchRadians,
              ),
            )
            .toList(growable: false),
      );
      if (_cancelRequested ||
          runCompleter.isCompleted ||
          !identical(_runCompleter, runCompleter)) {
        return;
      }
      stopwatch.stop();
      final result = Giro360CaptureResult(
        captureStatus: status,
        panorama: panorama,
        videoFile: File(status.videoPath),
        timelineFile: File(status.videoTimelinePath),
        selectedFrames: selectedFrames,
        stitchElapsed: stopwatch.elapsed,
      );
      _emit(
        Giro360CaptureEvent(
          stage: Giro360CaptureStage.completed,
          message: panorama.message ?? 'Panorama concluído.',
          status: status,
          result: result,
        ),
      );
      await _finishRun(runCompleter);
      runCompleter.complete(result);
    } catch (error, stackTrace) {
      if (_cancelRequested ||
          runCompleter.isCompleted ||
          !identical(_runCompleter, runCompleter)) {
        return;
      }
      _emit(
        Giro360CaptureEvent(
          stage: Giro360CaptureStage.failed,
          message: error.toString(),
          status: status,
          error: error,
        ),
      );
      await _completeError(
        error,
        stackTrace,
        runCompleter: runCompleter,
      );
    }
  }

  void _handleBackendError(Object error, StackTrace stackTrace) {
    _emit(
      Giro360CaptureEvent(
        stage: Giro360CaptureStage.failed,
        message: error.toString(),
        error: error,
      ),
    );
    unawaited(_completeError(error, stackTrace));
  }

  Future<void> _completeError(
    Object error,
    StackTrace stackTrace, {
    Completer<Giro360CaptureResult>? runCompleter,
  }) async {
    final completer = runCompleter ?? _runCompleter;
    if (!identical(_runCompleter, completer)) {
      return;
    }
    await _finishRun(completer);
    if (completer != null && !completer.isCompleted) {
      completer.completeError(error, stackTrace);
    }
  }

  Future<void> _finishRun(
    Completer<Giro360CaptureResult>? runCompleter,
  ) async {
    if (!identical(_runCompleter, runCompleter)) {
      return;
    }
    final subscription = _statusSubscription;
    _statusSubscription = null;
    await subscription?.cancel();
    if (!identical(_runCompleter, runCompleter)) {
      return;
    }
    _sessionDirectory = null;
    _config = null;
    _processingResult = false;
  }

  void _emit(Giro360CaptureEvent event) {
    if (!_events.isClosed) {
      _events.add(event);
    }
  }
}

class Giro360CaptureException implements Exception {
  const Giro360CaptureException(this.message);

  final String message;

  @override
  String toString() => message;
}

class Giro360CaptureCancelledException extends Giro360CaptureException {
  const Giro360CaptureCancelledException() : super('Captura cancelada.');
}
