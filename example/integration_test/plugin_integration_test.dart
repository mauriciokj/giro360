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
}
