import 'dart:ffi' as ffi;
import 'dart:io';
import 'dart:isolate';

import 'package:ffi/ffi.dart';

import 'stitch_frame_telemetry.dart';

typedef _NativeStitch = ffi.Int32 Function(
  ffi.Pointer<ffi.Pointer<Utf8>> imagePaths,
  ffi.Int32 imageCount,
  ffi.Pointer<Utf8> outputPath,
  ffi.Pointer<Utf8> errorBuffer,
  ffi.Int32 errorBufferLength,
);

typedef _DartStitch = int Function(
  ffi.Pointer<ffi.Pointer<Utf8>> imagePaths,
  int imageCount,
  ffi.Pointer<Utf8> outputPath,
  ffi.Pointer<Utf8> errorBuffer,
  int errorBufferLength,
);

typedef _NativeStitchWithTelemetry = ffi.Int32 Function(
  ffi.Pointer<ffi.Pointer<Utf8>> imagePaths,
  ffi.Int32 imageCount,
  ffi.Pointer<ffi.Double> actualYaws,
  ffi.Pointer<ffi.Double> actualPitches,
  ffi.Pointer<Utf8> outputPath,
  ffi.Pointer<Utf8> errorBuffer,
  ffi.Int32 errorBufferLength,
);

typedef _DartStitchWithTelemetry = int Function(
  ffi.Pointer<ffi.Pointer<Utf8>> imagePaths,
  int imageCount,
  ffi.Pointer<ffi.Double> actualYaws,
  ffi.Pointer<ffi.Double> actualPitches,
  ffi.Pointer<Utf8> outputPath,
  ffi.Pointer<Utf8> errorBuffer,
  int errorBufferLength,
);

typedef _NativeStitchWithOptions = ffi.Int32 Function(
  ffi.Pointer<ffi.Pointer<Utf8>> imagePaths,
  ffi.Int32 imageCount,
  ffi.Pointer<ffi.Double> actualYaws,
  ffi.Pointer<ffi.Double> actualPitches,
  ffi.Int32 guidedFillMode,
  ffi.Pointer<Utf8> outputPath,
  ffi.Pointer<Utf8> errorBuffer,
  ffi.Int32 errorBufferLength,
);

typedef _DartStitchWithOptions = int Function(
  ffi.Pointer<ffi.Pointer<Utf8>> imagePaths,
  int imageCount,
  ffi.Pointer<ffi.Double> actualYaws,
  ffi.Pointer<ffi.Double> actualPitches,
  int guidedFillMode,
  ffi.Pointer<Utf8> outputPath,
  ffi.Pointer<Utf8> errorBuffer,
  int errorBufferLength,
);

typedef _NativeStitchWithOptionsV2 = ffi.Int32 Function(
  ffi.Pointer<ffi.Pointer<Utf8>> imagePaths,
  ffi.Int32 imageCount,
  ffi.Pointer<ffi.Double> actualYaws,
  ffi.Pointer<ffi.Double> actualPitches,
  ffi.Int32 guidedFillMode,
  ffi.Int32 guidedAlignmentMode,
  ffi.Pointer<Utf8> outputPath,
  ffi.Pointer<Utf8> errorBuffer,
  ffi.Int32 errorBufferLength,
);

typedef _DartStitchWithOptionsV2 = int Function(
  ffi.Pointer<ffi.Pointer<Utf8>> imagePaths,
  int imageCount,
  ffi.Pointer<ffi.Double> actualYaws,
  ffi.Pointer<ffi.Double> actualPitches,
  int guidedFillMode,
  int guidedAlignmentMode,
  ffi.Pointer<Utf8> outputPath,
  ffi.Pointer<Utf8> errorBuffer,
  int errorBufferLength,
);

enum GuidedFallbackFillMode {
  blackBands(0),
  edgeFill(1);

  const GuidedFallbackFillMode(this.nativeValue);

  final int nativeValue;
}

enum GuidedAlignmentMode {
  telemetryOnly(0),
  horizontalRefine(1),
  localRefine(2),
  affineLocalRefine(3),
  videoRefine(4);

  const GuidedAlignmentMode(this.nativeValue);

  final int nativeValue;
}

extension GuidedAlignmentModeLabel on GuidedAlignmentMode {
  String get label {
    return switch (this) {
      GuidedAlignmentMode.telemetryOnly => 'telemetria pura',
      GuidedAlignmentMode.horizontalRefine => 'ORB horizontal',
      GuidedAlignmentMode.localRefine => 'ORB local',
      GuidedAlignmentMode.affineLocalRefine => 'ORB affine local',
      GuidedAlignmentMode.videoRefine => 'ORB vídeo coerente',
    };
  }
}

abstract interface class Giro360StitchBackend {
  Future<PanoramaStitchResult> stitch({
    required List<File> inputImages,
    required File outputFile,
    GuidedFallbackFillMode fallbackFillMode,
    GuidedAlignmentMode alignmentMode,
    List<Giro360FrameTelemetry>? frameTelemetry,
  });
}

class PanoramaStitcher implements Giro360StitchBackend {
  static const _guidedFallbackResultCode = 20;
  static const _messageBufferLength = 8192;

  @override
  Future<PanoramaStitchResult> stitch({
    required List<File> inputImages,
    required File outputFile,
    GuidedFallbackFillMode fallbackFillMode = GuidedFallbackFillMode.blackBands,
    GuidedAlignmentMode alignmentMode = GuidedAlignmentMode.horizontalRefine,
    List<Giro360FrameTelemetry>? frameTelemetry,
  }) async {
    if (inputImages.length < 2) {
      throw const StitchingException('São necessárias pelo menos 2 imagens.');
    }

    final inputPaths =
        inputImages.map((file) => file.path).toList(growable: false);
    final outputPathText = outputFile.path;
    final actualYaws =
        frameTelemetry != null && frameTelemetry.length == inputImages.length
            ? frameTelemetry.map((frame) => frame.actualYaw).toList()
            : null;
    final actualPitches =
        frameTelemetry != null && frameTelemetry.length == inputImages.length
            ? frameTelemetry.map((frame) => frame.actualPitch).toList()
            : null;

    return Isolate.run(() {
      final stitch = _loadStitchFunction();
      final stitchWithOptionsV2 = _tryLoadStitchWithOptionsV2Function();
      final stitchWithOptions = _tryLoadStitchWithOptionsFunction();
      final stitchWithTelemetry = _tryLoadStitchWithTelemetryFunction();
      final imagePaths = calloc<ffi.Pointer<Utf8>>(inputPaths.length);
      final allocatedStrings = <ffi.Pointer<Utf8>>[];
      final outputPath = outputPathText.toNativeUtf8();
      final errorBufferBytes = calloc<ffi.Char>(_messageBufferLength);
      final errorBuffer = errorBufferBytes.cast<Utf8>();
      ffi.Pointer<ffi.Double>? yawValues;
      ffi.Pointer<ffi.Double>? pitchValues;

      try {
        for (var i = 0; i < inputPaths.length; i++) {
          final nativePath = inputPaths[i].toNativeUtf8();
          allocatedStrings.add(nativePath);
          imagePaths[i] = nativePath;
        }

        final yaws = actualYaws;
        final pitches = actualPitches;
        final int result;
        if (stitchWithOptionsV2 != null && yaws != null && pitches != null) {
          yawValues = calloc<ffi.Double>(inputPaths.length);
          pitchValues = calloc<ffi.Double>(inputPaths.length);
          for (var i = 0; i < inputPaths.length; i++) {
            yawValues[i] = yaws[i];
            pitchValues[i] = pitches[i];
          }
          result = stitchWithOptionsV2(
            imagePaths,
            inputPaths.length,
            yawValues,
            pitchValues,
            fallbackFillMode.nativeValue,
            alignmentMode.nativeValue,
            outputPath,
            errorBuffer,
            _messageBufferLength,
          );
        } else if (stitchWithOptions != null &&
            yaws != null &&
            pitches != null) {
          yawValues = calloc<ffi.Double>(inputPaths.length);
          pitchValues = calloc<ffi.Double>(inputPaths.length);
          for (var i = 0; i < inputPaths.length; i++) {
            yawValues[i] = yaws[i];
            pitchValues[i] = pitches[i];
          }
          result = stitchWithOptions(
            imagePaths,
            inputPaths.length,
            yawValues,
            pitchValues,
            fallbackFillMode.nativeValue,
            outputPath,
            errorBuffer,
            _messageBufferLength,
          );
        } else if (stitchWithTelemetry != null &&
            yaws != null &&
            pitches != null) {
          yawValues = calloc<ffi.Double>(inputPaths.length);
          pitchValues = calloc<ffi.Double>(inputPaths.length);
          for (var i = 0; i < inputPaths.length; i++) {
            yawValues[i] = yaws[i];
            pitchValues[i] = pitches[i];
          }
          result = stitchWithTelemetry(
            imagePaths,
            inputPaths.length,
            yawValues,
            pitchValues,
            outputPath,
            errorBuffer,
            _messageBufferLength,
          );
        } else {
          result = stitch(
            imagePaths,
            inputPaths.length,
            outputPath,
            errorBuffer,
            _messageBufferLength,
          );
        }

        final nativeMessage = _NativeStitchMessage.parse(
          errorBuffer.toDartString(),
        );
        if (result != 0 && result != _guidedFallbackResultCode) {
          throw StitchingException(
            nativeMessage.message ?? 'Falha desconhecida no OpenCV.',
            diagnostics: nativeMessage.diagnostics,
          );
        }

        return PanoramaStitchResult(
          file: File(outputPathText),
          usedGuidedFallback: result == _guidedFallbackResultCode,
          message: nativeMessage.message,
          diagnostics: nativeMessage.diagnostics,
        );
      } finally {
        for (final string in allocatedStrings) {
          calloc.free(string);
        }
        calloc.free(imagePaths);
        calloc.free(outputPath);
        calloc.free(errorBufferBytes);
        if (yawValues != null) {
          calloc.free(yawValues);
        }
        if (pitchValues != null) {
          calloc.free(pitchValues);
        }
      }
    });
  }

  static _DartStitch _loadStitchFunction() {
    try {
      final library = _openLibrary();
      return library.lookupFunction<_NativeStitch, _DartStitch>(
        'giro360_stitch',
      );
    } on ArgumentError {
      throw const StitchingException(
        'O motor OpenCV nativo ainda não está empacotado neste build.',
      );
    } on UnsupportedError {
      throw const StitchingException(
        'Este dispositivo não conseguiu carregar o motor OpenCV nativo.',
      );
    } catch (error) {
      throw StitchingException(
        'Falha ao carregar o motor OpenCV nativo: $error',
      );
    }
  }

  static _DartStitchWithOptions? _tryLoadStitchWithOptionsFunction() {
    try {
      final library = _openLibrary();
      return library
          .lookupFunction<_NativeStitchWithOptions, _DartStitchWithOptions>(
        'giro360_stitch_with_options',
      );
    } catch (_) {
      return null;
    }
  }

  static _DartStitchWithOptionsV2? _tryLoadStitchWithOptionsV2Function() {
    try {
      final library = _openLibrary();
      return library
          .lookupFunction<_NativeStitchWithOptionsV2, _DartStitchWithOptionsV2>(
        'giro360_stitch_with_options_v2',
      );
    } catch (_) {
      return null;
    }
  }

  static _DartStitchWithTelemetry? _tryLoadStitchWithTelemetryFunction() {
    try {
      final library = _openLibrary();
      return library
          .lookupFunction<_NativeStitchWithTelemetry, _DartStitchWithTelemetry>(
        'giro360_stitch_with_telemetry',
      );
    } catch (_) {
      return null;
    }
  }

  static ffi.DynamicLibrary _openLibrary() {
    if (Platform.isAndroid) {
      return ffi.DynamicLibrary.open('libgiro360_stitcher.so');
    }
    if (Platform.isMacOS) {
      final overridePath = Platform.environment['GIRO360_STITCHER_LIBRARY'];
      if (overridePath != null && overridePath.isNotEmpty) {
        return ffi.DynamicLibrary.open(overridePath);
      }
      return ffi.DynamicLibrary.process();
    }
    if (Platform.isIOS) {
      return ffi.DynamicLibrary.process();
    }
    return ffi.DynamicLibrary.open('libgiro360_stitcher.dylib');
  }
}

class _NativeStitchMessage {
  const _NativeStitchMessage({
    required this.message,
    required this.diagnostics,
  });

  factory _NativeStitchMessage.parse(String rawMessage) {
    if (rawMessage.trim().isEmpty) {
      return const _NativeStitchMessage(
        message: null,
        diagnostics: <String, String>{},
      );
    }

    final lines = rawMessage.split('\n');
    final firstLine = lines.first.trim();
    final diagnostics = <String, String>{};

    for (final line in lines.skip(1)) {
      final separator = line.indexOf('=');
      if (separator <= 0) {
        continue;
      }

      final key = line.substring(0, separator).trim();
      final value = line.substring(separator + 1).trim();
      if (key.isNotEmpty) {
        diagnostics[key] = value;
      }
    }

    return _NativeStitchMessage(
      message: firstLine.isEmpty ? null : firstLine,
      diagnostics: Map.unmodifiable(diagnostics),
    );
  }

  final String? message;
  final Map<String, String> diagnostics;
}

class PanoramaStitchResult {
  const PanoramaStitchResult({
    required this.file,
    required this.usedGuidedFallback,
    this.diagnostics = const <String, String>{},
    this.message,
  });

  final File file;
  final bool usedGuidedFallback;
  final Map<String, String> diagnostics;
  final String? message;
}

class StitchingException implements Exception {
  const StitchingException(
    this.message, {
    this.diagnostics = const <String, String>{},
  });

  final String message;
  final Map<String, String> diagnostics;

  @override
  String toString() => message;
}
