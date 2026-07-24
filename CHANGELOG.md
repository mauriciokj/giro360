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
