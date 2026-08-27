import 'dart:convert';
import 'dart:io';

const _alignmentModes = <String>[
  'telemetryOnly',
  'horizontalRefine',
  'localRefine',
  'affineLocalRefine',
  'videoRefine',
];

Future<void> main(List<String> arguments) async {
  final options = _parseOptions(arguments);
  final timelinePath = options['timeline'];
  final outputPath = options['output'];
  final defaultLibrary = options['library'];
  final graphCutLibrary = options['graphcut-library'];
  if (timelinePath == null || outputPath == null || defaultLibrary == null) {
    stderr.writeln(
      'Usage: dart run tool/run_panorama_matrix.dart '
      '--timeline <timeline.json> --output <directory> '
      '--library <libgiro360_stitcher> '
      '[--graphcut-library <libgiro360_stitcher>] [--exhaustive true]',
    );
    exitCode = 64;
    return;
  }

  final timeline = File(timelinePath).absolute;
  final output = Directory(outputPath).absolute..createSync(recursive: true);
  final repository = File.fromUri(Platform.script).parent.parent;
  final exhaustive = options['exhaustive'] == 'true';
  final extractionLogs = <Map<String, Object?>>[];
  final runs = <Map<String, Object?>>[];

  final selections = exhaustive
      ? const [
          (lap: 1, bins: 30),
          (lap: 1, bins: 45),
          (lap: 1, bins: 60),
          (lap: 2, bins: 30),
          (lap: 2, bins: 45),
          (lap: 2, bins: 60),
        ]
      : const [
          (lap: 1, bins: 30),
          (lap: 1, bins: 45),
          (lap: 1, bins: 60),
          (lap: 2, bins: 30),
          (lap: 2, bins: 45),
          (lap: 2, bins: 60),
        ];

  for (final selection in selections) {
    final selectionName = 'lap${selection.lap}_${selection.bins}';
    final selectionDirectory = Directory('${output.path}/$selectionName');
    final selectedTimeline = File('${selectionDirectory.path}/timeline.json');
    if (!selectedTimeline.existsSync()) {
      stdout.writeln('Extracting $selectionName...');
      final extraction = await _run(
        Platform.resolvedExecutable,
        [
          'run',
          'tool/extract_timeline_lap.dart',
          '--timeline',
          timeline.path,
          '--lap',
          '${selection.lap}',
          '--bins',
          '${selection.bins}',
          '--output',
          selectionDirectory.path,
        ],
        workingDirectory: repository.path,
      );
      extractionLogs.add({
        'selection': selectionName,
        ...extraction.toJson(),
      });
      File('${selectionDirectory.path}/extraction_status.txt')
        ..createSync(recursive: true)
        ..writeAsStringSync(extraction.combinedOutput);
      if (extraction.exitCode != 0 || !selectedTimeline.existsSync()) {
        stderr.writeln('Failed to extract $selectionName.');
        continue;
      }
    } else {
      stdout.writeln('Reusing $selectionName extraction.');
    }

    final alignments = exhaustive || selection.bins == 60
        ? _alignmentModes
        : const ['videoRefine'];
    for (final alignment in alignments) {
      runs.add(
        await _stitch(
          repository: repository,
          timeline: selectedTimeline,
          output: output,
          selectionName: selectionName,
          alignment: alignment,
          fill: 'blackBands',
          library: defaultLibrary,
          engine: 'default',
        ),
      );
    }

    if (exhaustive || selection.bins == 60) {
      runs.add(
        await _stitch(
          repository: repository,
          timeline: selectedTimeline,
          output: output,
          selectionName: selectionName,
          alignment: 'videoRefine',
          fill: 'edgeFill',
          library: defaultLibrary,
          engine: 'default',
        ),
      );
      if (graphCutLibrary != null) {
        runs.add(
          await _stitch(
            repository: repository,
            timeline: selectedTimeline,
            output: output,
            selectionName: selectionName,
            alignment: 'videoRefine',
            fill: 'blackBands',
            library: graphCutLibrary,
            engine: 'graphcut_multiband',
          ),
        );
      }
    }
  }

  final successful = runs.where((run) => run['success'] == true).length;
  final manifest = {
    'generatedAt': DateTime.now().toUtc().toIso8601String(),
    'sourceTimeline': timeline.path,
    'profile': exhaustive ? 'exhaustive' : 'focused',
    'successfulRuns': successful,
    'totalRuns': runs.length,
    'extractions': extractionLogs,
    'runs': runs,
  };
  final manifestFile = File('${output.path}/matrix_manifest.json')
    ..writeAsStringSync(const JsonEncoder.withIndent('  ').convert(manifest));
  stdout.writeln('\nMatrix complete: $successful/${runs.length} successful');
  stdout.writeln('manifest=${manifestFile.path}');
  if (successful != runs.length) exitCode = 1;
}

Future<Map<String, Object?>> _stitch({
  required Directory repository,
  required File timeline,
  required Directory output,
  required String selectionName,
  required String alignment,
  required String fill,
  required String library,
  required String engine,
}) async {
  final name = '${selectionName}_${alignment}_${fill}_$engine';
  final panorama = File('${output.path}/$name.jpg');
  final status = File('${output.path}/${name}_status.txt');
  stdout.writeln('Processing $name...');
  final result = await _run(
    Platform.resolvedExecutable,
    [
      'run',
      'tool/reprocess_timeline.dart',
      '--timeline',
      timeline.path,
      '--output',
      panorama.path,
      '--library',
      File(library).absolute.path,
      '--alignment',
      alignment,
      '--fill',
      fill,
    ],
    workingDirectory: repository.path,
  );
  status.writeAsStringSync(result.combinedOutput);
  final success = result.exitCode == 0 && panorama.existsSync();
  stdout.writeln(
    '  ${success ? 'ok' : 'failed'} in ${result.durationMilliseconds} ms',
  );
  return {
    'name': name,
    'selection': selectionName,
    'alignment': alignment,
    'fill': fill,
    'engine': engine,
    'success': success,
    'exitCode': result.exitCode,
    'durationMilliseconds': result.durationMilliseconds,
    'panorama': panorama.path,
    'status': status.path,
    'panoramaBytes': panorama.existsSync() ? panorama.lengthSync() : 0,
  };
}

Future<_CommandResult> _run(
  String executable,
  List<String> arguments, {
  required String workingDirectory,
}) async {
  final stopwatch = Stopwatch()..start();
  final result = await Process.run(
    executable,
    arguments,
    workingDirectory: workingDirectory,
  );
  stopwatch.stop();
  return _CommandResult(
    exitCode: result.exitCode,
    stdout: '${result.stdout}',
    stderr: '${result.stderr}',
    durationMilliseconds: stopwatch.elapsedMilliseconds,
  );
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

class _CommandResult {
  const _CommandResult({
    required this.exitCode,
    required this.stdout,
    required this.stderr,
    required this.durationMilliseconds,
  });

  final int exitCode;
  final String stdout;
  final String stderr;
  final int durationMilliseconds;

  String get combinedOutput => [
        stdout.trim(),
        stderr.trim(),
      ].where((part) => part.isNotEmpty).join('\n');

  Map<String, Object?> toJson() => {
        'exitCode': exitCode,
        'durationMilliseconds': durationMilliseconds,
        'stdout': stdout,
        'stderr': stderr,
      };
}
