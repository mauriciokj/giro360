import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:giro360_capture/giro360_capture.dart';
import 'package:image_picker/image_picker.dart';
import 'package:path_provider/path_provider.dart';

void main() => runApp(const Giro360ExampleApp());

class Giro360ExampleApp extends StatelessWidget {
  const Giro360ExampleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        colorSchemeSeed: const Color(0xff20b8a6),
      ),
      home: const CaptureExampleScreen(),
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
  Giro360SupportInfo? _support;
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
    unawaited(_loadSupport());
  }

  Future<void> _loadSupport() async {
    try {
      final support = await _controller.supportInfo();
      if (mounted) setState(() => _support = support);
    } catch (error) {
      if (mounted) setState(() => _error = error.toString());
    }
  }

  Future<void> _prepareAndStart() async {
    var support = _support;
    if (support == null || !support.supported) return;
    if (support.recommendedMode == Giro360CaptureMode.videoOnly) {
      await _pickAndProcessVideo();
      return;
    }
    if (!support.ready) {
      try {
        support = await _controller.prepare();
        if (!mounted) return;
        setState(() => _support = support);
      } catch (error) {
        if (mounted) setState(() => _error = error.toString());
        return;
      }
    }
    if (!support.ready) return;
    await _start();
  }

  Future<void> _pickAndProcessVideo() async {
    final selected = await ImagePicker().pickVideo(source: ImageSource.gallery);
    if (selected == null || !mounted) return;
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
      final result = await _controller.processVideo(
        sourceVideo: File(selected.path),
        sessionDirectory: Directory('${root.path}/giro360/$stamp'),
      );
      if (mounted) setState(() => _panorama = result.panorama.file);
    } catch (error) {
      if (!mounted || error is Giro360CaptureCancelledException) return;
      setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _starting = false);
    }
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
      if (mounted) setState(() => _panorama = result.panorama.file);
    } catch (error) {
      if (!mounted || error is Giro360CaptureCancelledException) return;
      setState(() => _error = error.toString());
    } finally {
      if (mounted) setState(() => _starting = false);
    }
  }

  @override
  void dispose() {
    unawaited(_events?.cancel());
    unawaited(_controller.dispose());
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final support = _support;
    final status = _status;
    final active = _controller.isRunning;

    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        fit: StackFit.expand,
        children: [
          if (_panorama != null)
            InteractiveViewer(
              child: Image.file(_panorama!, fit: BoxFit.contain),
            )
          else
            const Giro360CapturePreview(),
          if (active &&
              support?.recommendedMode == Giro360CaptureMode.arTracked)
            const Center(
              child: Icon(Icons.add, color: Colors.white, size: 52),
            ),
          SafeArea(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (active || status != null)
                    CaptureProgressPanel(stage: _stage, status: status)
                  else
                    SupportPanel(support: support),
                  const Spacer(),
                  if (_error != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Text(
                        _error!,
                        style: const TextStyle(color: Color(0xffffb4ab)),
                      ),
                    ),
                  if (active)
                    OutlinedButton(
                      onPressed: _controller.cancel,
                      child: const Text('Cancelar'),
                    )
                  else
                    FilledButton.icon(
                      onPressed: support?.supported == true && !_starting
                          ? _prepareAndStart
                          : null,
                      icon: Icon(
                        support?.recommendedMode == Giro360CaptureMode.videoOnly
                            ? Icons.video_file_outlined
                            : support?.ready == true
                                ? Icons.videocam
                                : Icons.settings_suggest_outlined,
                      ),
                      label: Text(_buttonLabel(support)),
                    ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  String _buttonLabel(Giro360SupportInfo? support) {
    if (support == null) return 'Verificando compatibilidade';
    if (!support.supported) return 'Captura indisponível';
    if (support.recommendedMode == Giro360CaptureMode.videoOnly) {
      return _panorama == null ? 'Escolher vídeo' : 'Processar outro vídeo';
    }
    if (!support.ready) return 'Preparar captura';
    return _panorama == null ? 'Gravar duas voltas' : 'Capturar novamente';
  }
}

class SupportPanel extends StatelessWidget {
  const SupportPanel({required this.support, super.key});

  final Giro360SupportInfo? support;

  @override
  Widget build(BuildContext context) {
    final info = support;
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: const Color(0xe61a1d1d),
        border: Border.all(color: const Color(0x665a6664)),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            info == null
                ? 'Verificando este aparelho'
                : switch (info.recommendedMode) {
                    Giro360CaptureMode.arTracked => 'Modo completo disponível',
                    Giro360CaptureMode.videoOnly => 'Modo vídeo disponível',
                    Giro360CaptureMode.unavailable => 'Captura indisponível',
                  },
            style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: 6),
          Text(
            info?.reason ?? 'Consultando câmera, sensores e rastreamento AR...',
            style: const TextStyle(color: Color(0xffd1dbd9)),
          ),
          if (info != null) ...[
            const SizedBox(height: 12),
            for (final requirement in info.requirements)
              RequirementRow(requirement: requirement),
          ],
        ],
      ),
    );
  }
}

class RequirementRow extends StatelessWidget {
  const RequirementRow({required this.requirement, super.key});

  final Giro360RequirementStatus requirement;

  @override
  Widget build(BuildContext context) {
    final available = requirement.available;
    final pending = requirement.needsUserAction ||
        requirement.state == Giro360RequirementState.checking;
    final optional = !requirement.required && !available;
    final color = available
        ? const Color(0xff45d6a8)
        : optional
            ? const Color(0xff9fb6b2)
            : pending
                ? const Color(0xffffdf7e)
                : const Color(0xffffb4ab);
    final icon = available
        ? Icons.check_circle_outline
        : optional
            ? Icons.remove_circle_outline
            : pending
                ? Icons.info_outline
                : Icons.cancel_outlined;

    return Padding(
      padding: const EdgeInsets.only(top: 7),
      child: Row(
        children: [
          Icon(icon, color: color, size: 18),
          const SizedBox(width: 8),
          Expanded(child: Text(requirement.label)),
          const SizedBox(width: 8),
          Flexible(
            child: Text(
              requirement.message,
              textAlign: TextAlign.end,
              style: TextStyle(color: color, fontSize: 12),
            ),
          ),
        ],
      ),
    );
  }
}

class CaptureProgressPanel extends StatelessWidget {
  const CaptureProgressPanel(
      {required this.stage, required this.status, super.key});

  final Giro360CaptureStage? stage;
  final Giro360CaptureStatus? status;

  @override
  Widget build(BuildContext context) {
    final value = status;
    final message = stage == Giro360CaptureStage.stitching
        ? 'Costurando panorama no dispositivo'
        : value?.message ?? 'Iniciando captura';
    return Container(
      padding: const EdgeInsets.all(14),
      color: const Color(0xe61a1d1d),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            message,
            style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
          ),
          if (value != null) ...[
            const SizedBox(height: 10),
            LinearProgressIndicator(value: value.progress),
            const SizedBox(height: 8),
            Text(
              'Volta ${value.activeLap}/${value.requiredLaps} · '
              '${value.progressDegrees.toStringAsFixed(0)}° · '
              '${switch (value.captureSource) {
                'video' => 'vídeo + AR',
                'videoOnly' => 'somente vídeo',
                'importedVideo' => 'vídeo importado',
                _ => 'frames diretos',
              }}',
            ),
            if (value.captureSource == 'importedVideo' &&
                value.visualMotionSampleCount > 0) ...[
              const SizedBox(height: 4),
              Text(
                'ORB ${value.visualMotionMatchedPairCount}/'
                '${value.visualMotionSampleCount - 1} · '
                '${value.visualMotionReliable ? 'movimento confiável' : 'medição parcial'}',
                style: const TextStyle(color: Color(0xffa9bbb7), fontSize: 12),
              ),
            ],
          ],
        ],
      ),
    );
  }
}
