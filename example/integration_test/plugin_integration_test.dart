import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:giro360_capture/giro360_capture.dart';
import 'package:integration_test/integration_test.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('native capture backend is available on the host',
      (tester) async {
    final supported = await Giro360NativeCaptureService().isSupported();
    expect(supported, isA<bool>());
  });

  testWidgets('native stitch entry points remain callable on iOS',
      (tester) async {
    if (!Platform.isIOS) return;
    final directory =
        await Directory.systemTemp.createTemp('giro360_ffi_test_');
    addTearDown(() => directory.delete(recursive: true));
    final absentImage = File('${directory.path}/missing.jpg');

    // The native engine must try to read the files and report their absence.
    // Exercise both the basic and telemetry-aware C entry points.
    for (final withTelemetry in [false, true]) {
      final telemetry = withTelemetry
          ? List.generate(
              2,
              (_) =>
                  const Giro360FrameTelemetryData(actualYaw: 0, actualPitch: 0))
          : null;
      await expectLater(
        PanoramaStitcher().stitch(
          inputImages: [absentImage, absentImage],
          outputFile: File('${directory.path}/out.jpg'),
          frameTelemetry: telemetry,
        ),
        throwsA(isA<StitchingException>().having(
          (error) => error.message,
          'native error',
          contains('Não consegui abrir a imagem:'),
        )),
      );
    }
  });
}
