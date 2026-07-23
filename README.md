# giro360_capture

Plugin Flutter reutilizavel para capturar um panorama cilindrico no iPhone.
Ele grava duas voltas com ARKit, escolhe 30 keyframes de uma unica volta
coerente e executa a costura OpenCV no proprio aparelho.

## O que o pacote encapsula

- preview de camera ARKit com rastreamento 6-DoF;
- gravacao H.264 das duas voltas;
- progresso, direcao, inclinacao e deslocamento por eventos tipados;
- selecao de keyframes por faixa angular, nitidez e estabilidade;
- extracao dos frames selecionados a partir do MP4;
- costura cilindrica OpenCV pelo modo validado `videoRefine`;
- bloqueio de repouso da tela somente durante captura e finalizacao;
- injecao de backends para teste ou para futuras implementacoes nativas.

## Suporte atual

| Plataforma | Captura | Costura empacotada |
| --- | --- | --- |
| iOS 13+ com ARKit | Sim | Sim |
| Android | Nao | Nao |

Em plataformas sem implementacao, `isSupported()` retorna `false`. A API Dart
continua compilavel, o que permite compartilhar o mesmo app Flutter.

## Instalar a partir deste repositorio

```yaml
dependencies:
  giro360_capture:
    git:
      url: git@github.com:mauriciokj/giro360.git
      ref: v0.0.1
  path_provider: ^2.1.5
```

Para um repositorio publico, a URL HTTPS tambem funciona:

```yaml
url: https://github.com/mauriciokj/giro360.git
```

O app iOS hospedeiro precisa informar o uso da camera:

```xml
<key>NSCameraUsageDescription</key>
<string>A camera e usada para capturar o panorama.</string>
```

O plugin e registrado automaticamente pelo Flutter. Nao adicione classes Swift,
fontes C++ nem `OpenCV2` manualmente ao projeto do Runner. O `podspec` do pacote
declara o OpenCV e usa framework estatico para funcionar inclusive em hosts com
`use_frameworks!`.

## Uso completo

```dart
final controller = Giro360CaptureController();

final events = controller.events.listen((event) {
  final status = event.status;
  if (status != null) {
    print('${status.progressDegrees.toStringAsFixed(0)} graus');
  }
});

final root = await getApplicationSupportDirectory();
final session = Directory('${root.path}/giro360/minha-sessao');

final result = await controller.start(
  sessionDirectory: session,
  config: const Giro360CaptureConfig(
    requiredLaps: 2,
    binCount: 30,
    alignmentMode: GuidedAlignmentMode.videoRefine,
  ),
);

print(result.panorama.file.path);
print(result.videoFile.path);

await events.cancel();
await controller.dispose();
```

Para mostrar a imagem da camera, use a view opcional em uma tela Flutter:

```dart
const Stack(
  fit: StackFit.expand,
  children: [
    Giro360CapturePreview(),
    Center(child: Icon(Icons.add, color: Colors.white)),
  ],
)
```

## Eventos

`Giro360CaptureController.events` publica os estagios:

- `capturing`: ARKit esta gravando e medindo as duas voltas;
- `finalizing`: MP4 e timeline estao sendo fechados e os frames extraidos;
- `stitching`: OpenCV esta gerando o panorama;
- `completed`: o evento contem `Giro360CaptureResult`;
- `cancelled` e `failed`: a captura terminou sem panorama.

O `Giro360CaptureStatus` inclui progresso, tracking ARKit, sentido do giro,
deslocamento da lente, pitch, roll, quantidade de frames e caminhos dos
artefatos nativos.

## Arquivos gerados

Dentro do diretorio informado pelo host:

```text
giro360_capture.mp4
giro360_video_timeline.json
video_000.jpg ... video_029.jpg
giro360_panorama.jpg
```

O nome do panorama pode ser alterado em `Giro360CaptureConfig.outputFileName`.

## Exemplo independente

O diretorio `example/` e um aplicativo minimo que nao depende do Giro360:

```bash
cd example
flutter pub get
flutter run --release -d <device-id>
```

## Desenvolvimento

```bash
flutter analyze
flutter test

cd example
flutter analyze
flutter test
flutter build ios --release --no-codesign
```

O motor C++ e grande por concentrar os experimentos de alinhamento. A API
publica usa `videoRefine`, a alternativa visualmente validada no iPhone 16e.
