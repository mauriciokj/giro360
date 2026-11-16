import 'package:flutter/services.dart';

/// Addresses supplied by the linked iOS plugin; independent of symbol exports.
class IosStitchFunctions {
  const IosStitchFunctions({
    required this.stitch,
    required this.telemetry,
    required this.options,
    required this.optionsV2,
  });

  final int stitch;
  final int telemetry;
  final int options;
  final int optionsV2;

  static const _channel = MethodChannel('giro360_capture/methods');

  static Future<IosStitchFunctions> load() async {
    final value =
        await _channel.invokeMethod<Object?>('stitchFunctionAddresses');
    if (value is! Map) {
      throw const FormatException('Endereços do motor nativo inválidos.');
    }

    int address(String key) {
      final result = value[key];
      if (result is! int || result <= 0) {
        throw FormatException('Endereço do motor nativo inválido: $key.');
      }
      return result;
    }

    return IosStitchFunctions(
      stitch: address('stitch'),
      telemetry: address('telemetry'),
      options: address('options'),
      optionsV2: address('optionsV2'),
    );
  }
}
