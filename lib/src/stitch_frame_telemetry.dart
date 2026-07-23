abstract interface class Giro360FrameTelemetry {
  double get actualYaw;

  double get actualPitch;
}

class Giro360FrameTelemetryData implements Giro360FrameTelemetry {
  const Giro360FrameTelemetryData({
    required this.actualYaw,
    required this.actualPitch,
  });

  @override
  final double actualYaw;

  @override
  final double actualPitch;
}
