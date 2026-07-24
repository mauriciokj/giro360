## 0.1.0-dev.3

- Torna a importação de vídeo o fluxo padrão em Android sem ARCore.
- Adiciona `Giro360CaptureController.processVideo` sem exigir câmera ou sensores.
- Seleciona os frames por deslocamento visual ORB em vez de intervalos fixos.
- Analisa seis amostras por alvo e prioriza a volta com menor erro angular.
- Preserva a resolução original do vídeo na extração dos 30 frames finais.
- Normaliza vídeos verticais usando dimensões e metadado de orientação.
- Mantém a gravação CameraX como alternativa experimental para uso futuro.

## 0.1.0-dev.2

- Adiciona fallback Android por vídeo para aparelhos sem giroscópio ou ARCore.
- Grava duas voltas com CameraX, extrai 30 frames por volta e seleciona uma volta
  coerente antes da costura OpenCV.
- Expõe `Giro360CaptureMode` e informa `arTracked`, `videoOnly` ou `unavailable`
  no diagnóstico do aparelho.
- Mantém giroscópio e ARCore visíveis como recursos opcionais no fallback.

## 0.1.0-dev.1

- Adiciona captura Android com ARCore, preview nativo e keyframes diretos.
- Empacota OpenCV 4.14 e o stitcher C++ no APK via Prefab/CMake.
- Expõe diagnóstico tipado de câmera, sensores, permissão e serviço AR.
- Mostra a compatibilidade do aparelho antes de habilitar a captura.
- Mantém vídeo H.264 no iOS e identifica a origem Android como `directFrames`.

## 0.0.1

- Extrai captura ARKit por video para um plugin Flutter reutilizavel.
- Publica progresso por `EventChannel` e API tipada em Dart.
- Empacota o motor OpenCV e o modo de alinhamento `videoRefine` no pod iOS.
- Inclui controlador completo, preview opcional, testes e app de exemplo.
