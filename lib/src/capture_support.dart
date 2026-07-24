enum Giro360RequirementState {
  available,
  missing,
  permissionRequired,
  installRequired,
  checking,
  unavailable;

  static Giro360RequirementState fromNative(String value) {
    return switch (value) {
      'available' => Giro360RequirementState.available,
      'missing' => Giro360RequirementState.missing,
      'permission_required' => Giro360RequirementState.permissionRequired,
      'install_required' => Giro360RequirementState.installRequired,
      'checking' => Giro360RequirementState.checking,
      _ => Giro360RequirementState.unavailable,
    };
  }
}

class Giro360RequirementStatus {
  const Giro360RequirementStatus({
    required this.id,
    required this.label,
    required this.required,
    required this.state,
    required this.message,
  });

  factory Giro360RequirementStatus.fromMap(Map<Object?, Object?> map) {
    return Giro360RequirementStatus(
      id: map['id'] as String? ?? 'unknown',
      label: map['label'] as String? ?? 'Requisito desconhecido',
      required: map['required'] as bool? ?? true,
      state: Giro360RequirementState.fromNative(
        map['state'] as String? ?? 'unavailable',
      ),
      message: map['message'] as String? ?? '',
    );
  }

  final String id;
  final String label;
  final bool required;
  final Giro360RequirementState state;
  final String message;

  bool get available => state == Giro360RequirementState.available;

  bool get needsUserAction =>
      state == Giro360RequirementState.permissionRequired ||
      state == Giro360RequirementState.installRequired;
}

class Giro360SupportInfo {
  const Giro360SupportInfo({
    required this.platform,
    required this.supported,
    required this.ready,
    required this.reason,
    required this.requirements,
  });

  factory Giro360SupportInfo.fromMap(Map<Object?, Object?> map) {
    final rawRequirements = map['requirements'];
    return Giro360SupportInfo(
      platform: map['platform'] as String? ?? 'unsupported',
      supported: map['supported'] as bool? ?? false,
      ready: map['ready'] as bool? ?? false,
      reason: map['reason'] as String? ?? 'Compatibilidade desconhecida.',
      requirements: rawRequirements is List
          ? rawRequirements
              .whereType<Map<Object?, Object?>>()
              .map(Giro360RequirementStatus.fromMap)
              .toList(growable: false)
          : const <Giro360RequirementStatus>[],
    );
  }

  factory Giro360SupportInfo.unsupportedPlatform(String platform) {
    return Giro360SupportInfo(
      platform: platform,
      supported: false,
      ready: false,
      reason: 'A captura Giro360 ainda não está disponível nesta plataforma.',
      requirements: const <Giro360RequirementStatus>[],
    );
  }

  final String platform;
  final bool supported;
  final bool ready;
  final String reason;
  final List<Giro360RequirementStatus> requirements;

  bool get canPrepare =>
      supported && !ready && requirements.any((item) => item.needsUserAction);

  List<Giro360RequirementStatus> get blockingRequirements => requirements
      .where((item) => item.required && !item.available)
      .toList(growable: false);
}
