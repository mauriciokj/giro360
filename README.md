# giro360_capture

Plugin Flutter reutilizável para capturar panoramas cilíndricos no iOS e no
Android. O SDK acompanha duas voltas, seleciona keyframes de uma única volta
coerente e executa a costura OpenCV no próprio aparelho. O fluxo AR do Android
usa no mínimo 60 setores; o iOS conserva sua configuração independente.

## Suporte atual

| Plataforma | Rastreamento | Captura dos frames | Costura |
| --- | --- | --- | --- |
| iOS 13+ | ARKit 6-DoF | Vídeo H.264 | OpenCV embarcado |
| Android 7+ (API 24) | ARCore 6-DoF ou movimento visual ORB | Keyframes diretos ou vídeo importado | OpenCV 4.14 embarcado |

No Android, o SDK escolhe automaticamente entre `arTracked`, quando os sensores
e o ARCore estão disponíveis, e `videoOnly`, quando o panorama será produzido
a partir de um vídeo escolhido pelo aplicativo hospedeiro. A gravação CameraX
permanece no código como alternativa experimental, mas não é o fluxo padrão.

## Sensores e requisitos

| Requisito | Obrigatório | Uso |
| --- | --- | --- |
| Câmera traseira | Modo AR | Imagens e rastreamento visual durante a captura |
| Acelerômetro | Modo AR | Gravidade, estabilidade e fusão inercial |
| Giroscópio | Modo AR | Rotação precisa entre os keyframes |
| ARKit ou ARCore certificado | Modo AR | Pose 6-DoF e translação da lente |
| Google Play Services para RA | Modo AR no Android | Runtime e perfil do aparelho ARCore |
| Magnetômetro/bússola | Não | O progresso usa orientação relativa da sessão AR |
| Sensor de profundidade/ToF | Não | Não é usado no panorama cilíndrico atual |
| GPS | Não | A captura é inteiramente local |

No Android, ter acelerômetro e giroscópio não basta para `arTracked`: o modelo
também precisa ser certificado pelo ARCore. Sem esses recursos, `videoOnly`
continua disponível desde que o OpenCV esteja pronto. Esse
modo não exige câmera, permissão ou sensores dentro do app: ele recebe um MP4 já
gravado e mede o deslocamento entre amostras usando ORB. Grave duas voltas no
mesmo sentido, com o celular em pé e girando em torno da lente. A velocidade pode
variar, pois os frames são distribuídos pelo movimento visual acumulado.

## Posicionamento no tripé

O eixo vertical de rotação precisa passar pelo centro da lente traseira, não pelo
centro do celular ou tablet. Em aparelhos com a câmera na lateral, prender o
dispositivo pelo centro faz a lente descrever um círculo durante a volta. Esse
deslocamento produz paralaxe: objetos próximos mudam de posição em relação aos
distantes e podem aparecer quebrados ou duplicados no panorama.

Use este procedimento na orientação em que a captura será realizada:

1. Monte o dispositivo com a lente traseira diretamente acima do eixo de rotação
   da cabeça do tripé.
2. Use um trilho, braço lateral ou suporte deslocável quando a câmera não estiver
   no centro do aparelho.
3. Mantenha a lente nivelada, na mesma altura e sem inclinar o suporte durante as
   duas voltas.
4. Alinhe visualmente um objeto próximo com outro distante e gire alguns graus
   para cada lado. Ajuste o suporte enquanto os objetos mudarem de posição um em
   relação ao outro.
5. Afaste mãos, operador e partes do suporte do campo de visão e faça as duas
   voltas lentamente, sempre no mesmo sentido.

## Captura sem tripé

Segure o aparelho de forma que a tela permaneça voltada para você e gire o corpo
inteiro junto com ele. Não fique parado girando apenas o celular ou tablet: nessa
situação, a câmera traseira acaba apontando para o operador e o ambiente atrás
dele deixa de ser registrado. Nenhum método de costura consegue reconstruir uma
região que ficou oculta durante as duas voltas.

Procure manter a lente no mesmo ponto do espaço. Passos laterais, braços
esticados e inclinação vertical introduzem paralaxe. No tablet, segure-o perto
do eixo do corpo e faça passos curtos ao redor desse eixo, mantendo altura e
inclinação constantes.

Texto curto recomendado para captura manual:

> Mantenha a tela voltada para você e gire o corpo inteiro. Não gire apenas o
> aparelho. Preserve a altura e a posição da lente durante as duas voltas.

Durante o modo Android `arTracked`, o status também informa a velocidade de
rotação suavizada. A faixa ideal fica entre `0,17` e `0,28 rad/s`, cerca de 10 a
16 graus por segundo ou 23 a 36 segundos por volta:

```dart
final speed = status.rotationSpeed;
final degreesPerSecond = status.currentAngularSpeedDegrees;

// pending, tooSlow, ideal ou tooFast
print('$speed: ${degreesPerSecond.toStringAsFixed(0)} graus/s');
```

O app de exemplo apresenta esse valor em tempo real com estados coloridos. Os
alertas de translação da lente e sentido incorreto continuam tendo prioridade.

Texto curto recomendado para o helper do aplicativo:

> Posicione a lente sobre o centro do tripé. O tablet pode ficar deslocado. Antes
> de começar, gire um pouco para os lados e confirme que objetos próximos não se
> movem em relação aos distantes.

Nos testes com o Galaxy Tab, uma captura com `30/30` setores apresentou
deslocamento médio de `11,2 cm` e máximo de `15,1 cm`. Uma montagem melhor reduziu
esses valores para `6,5 cm` e `8,8 cm`, respectivamente, mas terminou com `29/30`
setores. A segunda imagem ficou melhor, embora ainda tenha repetido conteúdo no
fechamento do panorama. Isso indica duas frentes independentes: reduzir a
paralaxe na montagem e garantir no software a cobertura de todos os setores e o
fechamento circular entre `0` e `360` graus.

No modo Android `arTracked`, uma volta com pelo menos 90% dos setores medidos
pode recuperar as poucas lacunas diretamente do MP4, interpolando o instante
entre os setores vizinhos. O panorama só é enviado ao stitcher depois de formar
uma sequência ordenada completa. O campo `reconstructedBins` do diagnóstico
identifica quais setores foram recuperados; uma cobertura inferior a 90% é
rejeitada. O resultado também avisa quando a translação indica que a lente ficou
fora do eixo recomendado do tripé. Esse modo aplica um mínimo Android de 60
setores, mesmo quando o aplicativo hospedeiro conserva o valor padrão de 30. A
mudança não afeta a seleção nem a costura do iOS.

## Diagnóstico geométrico

O modo Android `arTracked` registra a calibração observada e o fechamento das
duas voltas no status e em `giro360_video_timeline.json`:

```dart
final calibration = result.captureStatus.cameraCalibration;
print(calibration.horizontalFovDegrees);
print(calibration.focalLengthVariationPercent);

final closure = result.captureStatus.loopClosure;
print(closure.meanTranslationMeters);
print(closure.visualInlierRatio);
print(closure.visualSpatialCoverage);
```

O fechamento visual usa ORB + RANSAC apenas como diagnóstico nesta etapa; ele
não modifica a costura final. A sequência completa dos próximos experimentos
está em [docs/ROADMAP_VISAO_COMPUTACIONAL.md](docs/ROADMAP_VISAO_COMPUTACIONAL.md).

## Diagnóstico ao abrir

```dart
final controller = Giro360CaptureController();
var support = await controller.supportInfo();

print(support.supported); // o hardware pode executar a captura
print(support.ready);     // os requisitos do modo recomendado estão prontos
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
      ref: codex/imported-video-pipeline
  path_provider: ^2.1.5
```

Também é possível usar `https://github.com/mauriciokj/giro360.git` em
repositórios públicos.

Esta é a referência de teste Android `0.1.0-dev.3`. A tag `v0.1.0` será criada
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

## Captura com AR

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
print(result.recordedVideoFile?.path);

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

## Processamento de vídeo importado

O seletor de arquivos pertence ao aplicativo hospedeiro. Depois que ele obtiver
um caminho local, entregue o arquivo ao SDK:

```dart
final selectedVideo = File(caminhoEscolhido);
final root = await getApplicationSupportDirectory();

final result = await controller.processVideo(
  sourceVideo: selectedVideo,
  sessionDirectory: Directory('${root.path}/giro360/minha-importacao'),
  config: const Giro360CaptureConfig(
    requiredLaps: 2,
    binCount: 30,
    alignmentMode: GuidedAlignmentMode.videoRefine,
  ),
);

print(result.panorama.file.path);
print(result.captureStatus.visualMotionReliable);
print(result.captureStatus.visualMotionMatchedPairCount);
```

O SDK analisa seis amostras para cada alvo, até 360 no perfil padrão. Ele estima
a direção e o movimento horizontal, separa as duas voltas e prioriza a que tiver
menor erro angular; a nitidez desempata seleções equivalentes. Somente os 30
frames finais são extraídos novamente na resolução original e enviados ao
stitcher.

## Matriz de testes offline

Uma única sessão que contenha `giro360_capture.mp4` e
`giro360_video_timeline.json` pode ser reprocessada quantas vezes forem
necessárias, sem repetir a captura. O executor abaixo preserva o vídeo original,
extrai 30, 45 e 60 quadros de cada volta e gera uma matriz focada com 18
panoramas. Nas sequências de 60 quadros ele compara todos os modos de alinhamento,
o preenchimento lateral e, quando informado, o motor GraphCut/multibanda.

```bash
flutter pub get

"$(dirname "$(which flutter)")/dart" run tool/run_panorama_matrix.dart \
  --timeline /caminho/da/sessao/giro360_video_timeline.json \
  --output /caminho/da/sessao/matrix \
  --library /caminho/libgiro360_stitcher.dylib \
  --graphcut-library /caminho/libgiro360_stitcher_graphcut.dylib
```

Cada panorama recebe um nome que identifica volta, número de quadros,
alinhamento, preenchimento e motor. O diretório também contém os diagnósticos
`*_status.txt` e `matrix_manifest.json`, com duração, resultado e caminho de cada
execução. Use `--exhaustive true` para combinar os cinco alinhamentos com todas
as quantidades de quadros; essa opção demora mais.

## Arquivos gerados

Ambas as plataformas geram:

```text
video_000.jpg ... video_029.jpg
giro360_panorama.jpg
```

O iOS e o modo Android `arTracked` também mantêm `giro360_capture.mp4` e
`giro360_video_timeline.json`, com os keyframes e métricas da pose. A importação
Android mantém `giro360_capture.mp4` e `giro360_video_only_timeline.json` com
`captureSource: importedVideo` e métricas da análise ORB.

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
Samsung SM-A127M. Nesse aparelho sem giroscópio e sem ARCore, o seletor nativo
abriu sem solicitar câmera e importou um vídeo de 1:03. O refinamento atual usa
até 360 amostras antes de enviar 30 frames em resolução original ao OpenCV. A
gravação CameraX continua disponível internamente para experimentos futuros;
`arTracked` ainda requer teste em hardware Android certificado.
