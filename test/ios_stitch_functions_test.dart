import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:giro360_capture/src/ios_stitch_functions.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('giro360_capture/methods');
  final messenger =
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;

  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  test('loads every entry point returned by the iOS plugin', () async {
    messenger.setMockMethodCallHandler(channel, (call) async {
      expect(call.method, 'stitchFunctionAddresses');
      return {'stitch': 1, 'telemetry': 2, 'options': 3, 'optionsV2': 4};
    });

    final functions = await IosStitchFunctions.load();
    expect(functions.stitch, 1);
    expect(functions.telemetry, 2);
    expect(functions.options, 3);
    expect(functions.optionsV2, 4);
  });

  test('rejects missing, zero and non-integer entry points before FFI',
      () async {
    for (final invalid in [null, 0, '123']) {
      messenger.setMockMethodCallHandler(
          channel,
          (_) async => {
                'stitch': 1,
                'telemetry': 2,
                'options': 3,
                'optionsV2': invalid,
              });
      await expectLater(IosStitchFunctions.load(), throwsFormatException);
    }
  });
}
