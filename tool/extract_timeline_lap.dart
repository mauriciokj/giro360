import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

void main(List<String> arguments) {
  final options = _parseOptions(arguments);
  final timelinePath = options['timeline'];
  final outputPath = options['output'];
  final lapNumber = int.tryParse(options['lap'] ?? '');
  if (timelinePath == null || outputPath == null || lapNumber == null) {
    stderr.writeln(
      'Usage: dart run tool/extract_timeline_lap.dart '
      '--timeline <timeline.json> --lap <1|2> --output <directory> '
      '[--bins <count>] [--ffmpeg <path>]',
    );
    exitCode = 64;
    return;
  }

  final sourceFile = File(timelinePath);
  final root =
      jsonDecode(sourceFile.readAsStringSync()) as Map<String, Object?>;
  final samples = (root['timeline'] as List<Object?>)
      .cast<Map<String, Object?>>()
      .where((sample) => sample['trackingState'] == 'normal')
      .toList(growable: false);
  final binCount = int.tryParse(options['bins'] ?? '') ??
      (root['binCount'] as num?)?.toInt() ??
      30;
  if (lapNumber < 1 || samples.isEmpty) {
    stderr.writeln('Invalid lap or empty timeline.');
    exitCode = 65;
    return;
  }

  final outputDirectory = Directory(outputPath)..createSync(recursive: true);
  final video = File('${sourceFile.parent.path}/giro360_capture.mp4');
  if (!video.existsSync()) {
    stderr.writeln('Missing source video: ${video.path}');
    exitCode = 66;
    return;
  }

  final selected = <Map<String, Object?>>[];
  final step = math.pi * 2 / binCount;
  final lapStart = (lapNumber - 1) * math.pi * 2;
  for (var bin = 0; bin < binCount; bin += 1) {
    final target = bin == 0 ? lapStart + math.pi * 2 : lapStart + bin * step;
    final candidates = samples.where((sample) {
      final yaw = (sample['relativeYawRadians'] as num).toDouble();
      return yaw >= lapStart - step && yaw <= lapStart + math.pi * 2 + step;
    });
    if (candidates.isEmpty) {
      stderr.writeln('No tracked sample for lap $lapNumber, bin $bin.');
      exitCode = 67;
      return;
    }

    final best = candidates.reduce((first, second) {
      return _sampleCost(first, target) <= _sampleCost(second, target)
          ? first
          : second;
    });
    final timestamp = (best['videoTimeSeconds'] as num).toDouble();
    final frameFile = File(
      '${outputDirectory.path}/video_${bin.toString().padLeft(3, '0')}.jpg',
    );
    final ffmpeg = options['ffmpeg'] ?? '/opt/homebrew/bin/ffmpeg';
    final extraction = Process.runSync(
      ffmpeg,
      [
        '-hide_banner',
        '-loglevel',
        'error',
        '-ss',
        timestamp.toStringAsFixed(6),
        '-i',
        video.path,
        '-frames:v',
        '1',
        '-q:v',
        '2',
        '-y',
        frameFile.path,
      ],
    );
    if (extraction.exitCode != 0 || !frameFile.existsSync()) {
      stderr.writeln('Failed to extract bin $bin: ${extraction.stderr}');
      exitCode = 68;
      return;
    }

    final yaw = (best['relativeYawRadians'] as num).toDouble();
    selected.add({
      'binIndex': bin,
      'lapIndex': lapNumber - 1,
      'filePath': frameFile.path,
      'targetYawRadians': bin * step,
      'relativeYawRadians': _positiveModulo(yaw, math.pi * 2),
      'pitchRadians': best['pitchRadians'],
      'rollRadians': best['rollRadians'],
      'translationMeters': best['translationMeters'],
      'angularSpeedRadiansPerSecond': best['angularSpeedRadiansPerSecond'],
      'centerErrorRadians': (yaw - target).abs(),
      'trackingState': best['trackingState'],
      'frameTimestampSeconds': timestamp,
      'videoTimeSeconds': timestamp,
      'selectionSource': 'offline_coherent_lap',
    });
  }

  final outputTimeline = Map<String, Object?>.from(root)
    ..['selectedLap'] = lapNumber
    ..['binCount'] = binCount
    ..['selectedFrameStartSeconds'] = selected
        .map((frame) => frame['videoTimeSeconds'] as double)
        .reduce(math.min)
    ..['selectedFrameEndSeconds'] = selected
        .map((frame) => frame['videoTimeSeconds'] as double)
        .reduce(math.max)
    ..['frames'] = selected;
  final outputTimelineFile = File('${outputDirectory.path}/timeline.json');
  outputTimelineFile.writeAsStringSync(
    const JsonEncoder.withIndent('  ').convert(outputTimeline),
  );

  final averageTranslation = selected
          .map((frame) => frame['translationMeters'] as num)
          .fold<double>(0, (sum, value) => sum + value.toDouble()) /
      selected.length;
  final pitchValues = selected
      .map((frame) => (frame['pitchRadians'] as num).toDouble())
      .toList(growable: false);
  stdout.writeln('timeline=${outputTimelineFile.path}');
  stdout.writeln('frames=${selected.length}');
  stdout.writeln('averageTranslationMeters=$averageTranslation');
  stdout.writeln(
    'pitchSpanDegrees=${(_span(pitchValues) * 180 / math.pi).toStringAsFixed(3)}',
  );
}

double _sampleCost(Map<String, Object?> sample, double targetYaw) {
  final yaw = (sample['relativeYawRadians'] as num).toDouble();
  final pitch = (sample['pitchRadians'] as num).toDouble().abs();
  final roll = (sample['rollRadians'] as num).toDouble().abs();
  final speed =
      (sample['angularSpeedRadiansPerSecond'] as num).toDouble().abs();
  return (yaw - targetYaw).abs() * 30 + pitch * 2 + roll + speed * 0.35;
}

double _positiveModulo(double value, double modulus) =>
    ((value % modulus) + modulus) % modulus;

double _span(List<double> values) =>
    values.reduce(math.max) - values.reduce(math.min);

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
