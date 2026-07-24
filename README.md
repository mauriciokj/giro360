# giro360_capture

Plugin Flutter reutilizável para capturar panoramas cilíndricos no iOS e no
Android. O SDK acompanha duas voltas, seleciona 30 keyframes de uma única volta
coerente e executa a costura OpenCV no próprio aparelho.

## Suporte atual

| Plataforma | Rastreamento | Captura dos frames | Costura |
| --- | --- | --- | --- |
| iOS 13+ | ARKit 6-DoF | Vídeo H.264 | OpenCV embarcado |
| Android 7+ (API 24) | ARCore 6-DoF ou vídeo temporizado | Keyframes diretos ou vídeo H.264 | OpenCV 4.14 embarcado |

No Android, o SDK escolhe automaticamente entre `arTracked`, quando os sensores
e o ARCore estão disponíveis, e `videoOnly`, quando há apenas câmera traseira.
O modo por vídeo grava duas voltas de 15 segundos, extrai 30 candidatos por
volta e envia uma única volta coerente ao mesmo motor OpenCV.

## Sensores e requisitos

| Requisito | Obrigatório | Uso |
| --- | --- | --- |
| Câmera traseira | Sim | Imagens e rastreamento visual |
| Acelerômetro | Modo AR | Gravidade, estabilidade e fusão inercial |
| Giroscópio | Modo AR | Rotação precisa entre os keyframes |
| ARKit ou ARCore certificado | Modo AR | Pose 6-DoF e translação da lente |
| Google Play Services para RA | Modo AR no Android | Runtime e perfil do aparelho ARCore |
| Magnetômetro/bússola | Não | O progresso usa orientação relativa da sessão AR |
| Sensor de profundidade/ToF | Não | Não é usado no panorama cilíndrico atual |
| GPS | Não | A captura é inteiramente local |

No Android, ter acelerômetro e giroscópio não basta para `arTracked`: o modelo
também precisa ser certificado pelo ARCore. Sem esses recursos, `videoOnly`
continua disponível desde que câmera, permissão e OpenCV estejam prontos. Esse
modo não mede a pose física; velocidade constante e duas voltas no mesmo sentido
são importantes para distribuir os frames corretamente.

## Diagnóstico ao abrir

```dart
final controller = Giro360CaptureController();
var support = await controller.supportInfo();

print(support.supported); // o hardware pode executar a captura
print(support.ready);     // permissão e serviço AR estão prontos
print(support.recommendedMode); // arTracked, videoOnly ou unavailable
print(support.reason);    // motivo legível para o usuário

for (final requirement in support.requirements) {
  print('${requirement.label}: ${requirement.message}');
}

if (support.canPrepare) {
  support = await controller.prepare();
}
```

`prepare()` solicita a câmera e só abre a instalação ou atualização do Google
Play Services para RA quando o aparelho puder usar `arTracked`. Só habilite a
captura quando `support.ready` for `true`.

## Instalação

```yaml
dependencies:
  giro360_capture:
    git:
      url: git@github.com:mauriciokj/giro360.git
      ref: codex/video-only-fallback
  path_provider: ^2.1.5
```

Também é possível usar `https://github.com/mauriciokj/giro360.git` em
repositórios públicos.

Esta é a referência de teste Android `0.1.0-dev.2`. A tag `v0.1.0` será criada
depois da validação das duas modalidades em mais aparelhos.

### iOS

Declare o uso da câmera em `ios/Runner/Info.plist`:

```xml
<key>NSCameraUsageDescription</key>
<string>A câmera é usada para capturar o panorama.</string>
```

O plugin requer iOS 13 ou superior. O `podspec` já inclui ARKit, OpenCV e o
stitcher C++; não copie código nativo para o Runner.

### Android

O host precisa usar `minSdk` 24 ou superior. Permissão de câmera, recursos de
sensores e metadado ARCore opcional são mesclados automaticamente pelo plugin.
O pacote usa ARCore `1.54.0`, OpenCV `4.14.0`, CMake e NDK.

## Captura

```dart
final controller = Giro360CaptureController();
final root = await getApplicationSupportDirectory();
final session = Directory('${root.path}/giro360/minha-sessao');

final events = controller.events.listen((event) {
  final status = event.status;
  if (status != null) {
    print('${status.progressDegrees.toStringAsFixed(0)} graus');
  }
});

final result = await controller.start(
  sessionDirectory: session,
  config: const Giro360CaptureConfig(
    requiredLaps: 2,
    binCount: 30,
    alignmentMode: GuidedAlignmentMode.videoRefine,
  ),
);

print(result.panorama.file.path);
print(result.recordedVideoFile?.path); // iOS e Android no modo videoOnly

await events.cancel();
await controller.dispose();
```

O preview nativo é o mesmo nas duas plataformas:

```dart
const Stack(
  fit: StackFit.expand,
  children: [
    Giro360CapturePreview(),
    Center(child: Icon(Icons.add, color: Colors.white)),
  ],
)
```

## Arquivos gerados

Ambas as plataformas geram:

```text
video_000.jpg ... video_029.jpg
giro360_panorama.jpg
```

O iOS também mantém `giro360_capture.mp4` e
`giro360_video_timeline.json`. No Android, `arTracked` salva
`giro360_android_timeline.json` com `captureSource: directFrames`; `videoOnly`
mantém `giro360_capture.mp4` e `giro360_video_only_timeline.json` com
`captureSource: videoOnly`.

## Exemplo e validação

```bash
cd example
flutter pub get
flutter run -d <device-id>
```

```bash
flutter analyze
flutter test

cd example
flutter analyze
flutter test
flutter build apk --debug --target-platform android-arm64
flutter build ios --release --no-codesign
```

O build Android, o carregamento do OpenCV e o diagnóstico foram validados em um
Samsung SM-A127M. Nesse aparelho sem giroscópio e sem ARCore, o fallback abriu a
prévia CameraX, gravou o MP4, extraiu 60 frames e entregou a volta selecionada ao
OpenCV sem encerrar o processo. A qualidade do panorama `videoOnly` ainda deve
ser validada com uma rotação manual real; `arTracked` ainda requer teste em
hardware Android certificado.
