import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:giro360_capture/giro360_capture.dart';
import 'package:path_provider/path_provider.dart';

void main() => runApp(const Giro360ExampleApp());

class Giro360ExampleApp extends StatelessWidget {
  const Giro360ExampleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return const MaterialApp(
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.dark,
      home: CaptureExampleScreen(),
    );
  }
}

class CaptureExampleScreen extends StatefulWidget {
  const CaptureExampleScreen({super.key});

  @override
  State<CaptureExampleScreen> createState() => _CaptureExampleScreenState();
}

class _CaptureExampleScreenState extends State<CaptureExampleScreen> {
  final _controller = Giro360CaptureController();
  StreamSubscription<Giro360CaptureEvent>? _events;
  Giro360CaptureStatus? _status;
  Giro360CaptureStage? _stage;
  File? _panorama;
  String? _error;
  bool _starting = false;

  @override
  void initState() {
    super.initState();
    _events = _controller.events.listen((event) {
      if (!mounted) return;
      setState(() {
        _status = event.status ?? _status;
        _stage = event.stage;
        _panorama = event.result?.panorama.file ?? _panorama;
        _error =
            event.stage == Giro360CaptureStage.failed ? event.message : null;
      });
    });
  }

  @override
  void dispose() {
    unawaited(_events?.cancel());
    unawaited(_controller.dispose());
    super.dispose();
  }

  Future<void> _start() async {
    setState(() {
      _starting = true;
      _panorama = null;
      _status = null;
      _error = null;
    });
    try {
      final root = await getApplicationSupportDirectory();
      final stamp =
          DateTime.now().toUtc().toIso8601String().replaceAll(':', '-');
      final result = await _controller.start(
        sessionDirectory: Directory('${root.path}/giro360/$stamp'),
      );
      if (!mounted) return;
      setState(() => _panorama = result.panorama.file);
    } catch (error) {
      if (!mounted || error is Giro360CaptureCancelledException) return;
      setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _starting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final status = _status;
    final active = _controller.isRunning;
    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        fit: StackFit.expand,
        children: [
          if (_panorama != null)
            InteractiveViewer(
                child: Image.file(_panorama!, fit: BoxFit.contain))
          else
            const Giro360CapturePreview(),
          if (active)
            const Center(
              child: Icon(Icons.add, color: Colors.white, size: 52),
            ),
          SafeArea(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    _stage == Giro360CaptureStage.stitching
                        ? 'Costurando panorama'
                        : status?.message ?? 'Giro360 Capture',
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 18,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  if (status != null) ...[
                    const SizedBox(height: 10),
                    LinearProgressIndicator(value: status.progress),
                  ],
                  const Spacer(),
                  if (_error != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Text(
                        _error!,
                        style: const TextStyle(color: Colors.redAccent),
                      ),
                    ),
                  if (active)
                    OutlinedButton(
                      onPressed: _controller.cancel,
                      child: const Text('Cancelar'),
                    )
                  else
                    FilledButton.icon(
                      onPressed: _starting ? null : _start,
                      icon: const Icon(Icons.videocam),
                      label: Text(
                        _panorama == null
                            ? 'Gravar duas voltas'
                            : 'Capturar novamente',
                      ),
                    ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
