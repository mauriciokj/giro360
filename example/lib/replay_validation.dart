// Device validation entry point; the normal SDK example is lib/main.dart.
// Copy the original MP4/timeline to Documents/Giro360Validation before launch.
import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:giro360_capture/giro360_capture.dart';
import 'package:path_provider/path_provider.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MaterialApp(home: ReplayValidation()));
}

class ReplayValidation extends StatefulWidget {
  const ReplayValidation({super.key});
  @override
  State<ReplayValidation> createState() => _ReplayValidationState();
}

class _ReplayValidationState extends State<ReplayValidation> {
  String message = 'Validando a montagem no dispositivo…';
  @override
  void initState() {
    super.initState();
    run();
  }

  Future<void> run() async {
    final documents = await getApplicationDocumentsDirectory();
    final input = Directory('${documents.path}/Giro360Validation');
    final output = Directory('${documents.path}/Giro360ValidationResults');
    await output.create(recursive: true);
    final report = File('${output.path}/latest.json');
    final watch = Stopwatch()..start();
    try {
      final selection = await Giro360NativeCaptureService().selectRecordedVideo(
        videoFile: File('${input.path}/giro360_capture.mp4'),
        timelineFile: File('${input.path}/giro360_video_timeline.json'),
        outputDirectory: Directory(
            '${output.path}/frames_${DateTime.now().microsecondsSinceEpoch}'),
      );
      if (mounted) setState(() => message = 'Imagens selecionadas. Montando…');
      final selectionMs = watch.elapsedMilliseconds;
      final panorama = await PanoramaStitcher().stitch(
        inputImages: selection.frames.map((f) => File(f.filePath)).toList(),
        outputFile: File('${output.path}/panorama.jpg'),
        fallbackFillMode: GuidedFallbackFillMode.blackBands,
        alignmentMode: GuidedAlignmentMode.videoRefine,
        frameTelemetry: selection.frames
            .map((f) => Giro360FrameTelemetryData(
                  actualYaw: f.relativeYawRadians,
                  actualPitch: f.pitchRadians,
                ))
            .toList(),
      );
      await report.writeAsString(const JsonEncoder.withIndent('  ').convert({
        'success': true,
        'selectionMilliseconds': selectionMs,
        'stitchMilliseconds': watch.elapsedMilliseconds - selectionMs,
        'totalMilliseconds': watch.elapsedMilliseconds,
        'selection': selection.diagnostics,
        'frames': selection.frames.map((f) => f.toMap()).toList(),
        'panoramaPath': panorama.file.path,
        'stitchDiagnostics': panorama.diagnostics,
        'stitchMessage': panorama.message,
      }));
      if (mounted) {
        setState(() => message =
            'Validação concluída em ${(watch.elapsedMilliseconds / 1000).toStringAsFixed(1)} s.');
      }
    } catch (error, stack) {
      await report.writeAsString(jsonEncode({
        'success': false,
        'error': error.toString(),
        'stack': stack.toString()
      }));
      if (mounted) setState(() => message = 'Falha na validação: $error');
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Validação do SDK')),
        body: Center(
            child: Padding(
                padding: const EdgeInsets.all(24), child: Text(message))),
      );
}
