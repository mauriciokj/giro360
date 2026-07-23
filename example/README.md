# Exemplo giro360_capture

Aplicativo Flutter minimo que usa somente a API publica do plugin.

```bash
flutter pub get
flutter run --release -d <device-id>
```

No iPhone, toque em `Gravar duas voltas`, mantenha a lente no mesmo ponto e
gire duas vezes no mesmo sentido. O exemplo mostra os eventos de progresso e o
panorama retornado por `Giro360CaptureController`.
