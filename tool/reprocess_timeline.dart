import 'dart:convert';
import 'dart:ffi' as ffi;
import 'dart:io';

import 'package:ffi/ffi.dart';

typedef _NativeStitch = ffi.Int32 Function(
  ffi.Pointer<ffi.Pointer<Utf8>> imagePaths,
  ffi.Int32 imageCount,
  ffi.Pointer<ffi.Double> actualYaws,
  ffi.Pointer<ffi.Double> actualPitches,
  ffi.Int32 fillMode,
  ffi.Int32 alignmentMode,
  ffi.Pointer<Utf8> outputPath,
  ffi.Pointer<Utf8> messageBuffer,
  ffi.Int32 messageBufferLength,
);

typedef _DartStitch = int Function(
  ffi.Pointer<ffi.Pointer<Utf8>> imagePaths,
  int imageCount,
  ffi.Pointer<ffi.Double> actualYaws,
  ffi.Pointer<ffi.Double> actualPitches,
  int fillMode,
  int alignmentMode,
  ffi.Pointer<Utf8> outputPath,
  ffi.Pointer<Utf8> messageBuffer,
  int messageBufferLength,
);

const _alignmentModes = <String, int>{
  'telemetryOnly': 0,
  'horizontalRefine': 1,
  'localRefine': 2,
  'affineLocalRefine': 3,
  'videoRefine': 4,
};

const _fillModes = <String, int>{
  'blackBands': 0,
  'edgeFill': 1,
};

void main(List<String> arguments) {
  final options = _parseOptions(arguments);
  final timelinePath = options['timeline'];
  final outputPath = options['output'];
  final libraryPath =
      options['library'] ?? Platform.environment['GIRO360_STITCHER_LIBRARY'];
  if (timelinePath == null || outputPath == null || libraryPath == null) {
    stderr.writeln(
      'Usage: dart run tool/reprocess_timeline.dart '
      '--timeline <timeline.json> --output <panorama.jpg> '
      '--library <libgiro360_stitcher> '
      '[--alignment videoRefine] [--fill blackBands]',
    );
    exitCode = 64;
    return;
  }

  final alignmentName = options['alignment'] ?? 'videoRefine';
  final fillName = options['fill'] ?? 'blackBands';
  final alignmentMode = _alignmentModes[alignmentName];
  final fillMode = _fillModes[fillName];
  if (alignmentMode == null || fillMode == null) {
    stderr.writeln('Invalid alignment or fill mode.');
    exitCode = 64;
    return;
  }

  final timelineFile = File(timelinePath);
  final timeline =
      jsonDecode(timelineFile.readAsStringSync()) as Map<String, Object?>;
  final frames =
      (timeline['frames'] as List<Object?>).cast<Map<String, Object?>>()
        ..sort(
          (first, second) =>
              (first['binIndex'] as num).compareTo(second['binIndex'] as num),
        );
  if (frames.length < 2) {
    stderr.writeln('Timeline must contain at least two selected frames.');
    exitCode = 65;
    return;
  }

  final sessionDirectory = timelineFile.parent;
  final imageFiles = frames.map((frame) {
    final originalPath = frame['filePath'] as String;
    return File('${sessionDirectory.path}/${_basename(originalPath)}');
  }).toList(growable: false);
  final missing = imageFiles.where((file) => !file.existsSync()).toList();
  if (missing.isNotEmpty) {
    stderr.writeln('Missing frame: ${missing.first.path}');
    exitCode = 66;
    return;
  }

  final library = ffi.DynamicLibrary.open(libraryPath);
  final stitch = library.lookupFunction<_NativeStitch, _DartStitch>(
    'giro360_stitch_with_options_v2',
  );
  const bufferLength = 64 * 1024;
  final paths = calloc<ffi.Pointer<Utf8>>(frames.length);
  final yaws = calloc<ffi.Double>(frames.length);
  final pitches = calloc<ffi.Double>(frames.length);
  final output = outputPath.toNativeUtf8();
  final messageBytes = calloc<ffi.Char>(bufferLength);
  final message = messageBytes.cast<Utf8>();
  final allocatedPaths = <ffi.Pointer<Utf8>>[];

  try {
    for (var index = 0; index < frames.length; index += 1) {
      final path = imageFiles[index].path.toNativeUtf8();
      allocatedPaths.add(path);
      paths[index] = path;
      yaws[index] = (frames[index]['relativeYawRadians'] as num).toDouble();
      pitches[index] = (frames[index]['pitchRadians'] as num).toDouble();
    }

    final result = stitch(
      paths,
      frames.length,
      yaws,
      pitches,
      fillMode,
      alignmentMode,
      output,
      message,
      bufferLength,
    );
    stdout.writeln('result=$result');
    stdout.writeln('alignment=$alignmentName');
    stdout.writeln('fill=$fillName');
    stdout.writeln('output=$outputPath');
    final details = message.toDartString();
    if (details.isNotEmpty) stdout.writeln(details);
    if (result != 0 && result != 20) exitCode = result;
  } finally {
    for (final path in allocatedPaths) {
      calloc.free(path);
    }
    calloc.free(paths);
    calloc.free(yaws);
    calloc.free(pitches);
    calloc.free(output);
    calloc.free(messageBytes);
  }
}

Map<String, String> _parseOptions(List<String> arguments) {
  final options = <String, String>{};
  for (var index = 0; index + 1 < arguments.length; index += 1) {
    final argument = arguments[index];
    if (!argument.startsWith('--')) continue;
    options[argument.substring(2)] = arguments[index + 1];
    index += 1;
  }
  return options;
}

String _basename(String path) => path.substring(path.lastIndexOf('/') + 1);
