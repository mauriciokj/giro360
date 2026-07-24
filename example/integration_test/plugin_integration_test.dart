import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:giro360_capture/giro360_capture.dart';
import 'package:integration_test/integration_test.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('native capture backend is available on the host',
      (tester) async {
    final support = await Giro360NativeCaptureService().supportInfo();
    debugPrint('Giro360 support: ${support.reason}');
    for (final requirement in support.requirements) {
      debugPrint(
        '${requirement.id}: ${requirement.state.name} (${requirement.message})',
      );
    }
    expect(support.supported, isA<bool>());
    expect(support.requirements, isNotEmpty);
    if (Platform.isAndroid) {
      expect(
        support.requirements.map((item) => item.id),
        containsAll(<String>[
          'rear_camera',
          'accelerometer',
          'gyroscope',
          'motion_tracking',
          'camera_permission',
          'ar_service',
          'native_stitching',
        ]),
      );
    }
  });
}
