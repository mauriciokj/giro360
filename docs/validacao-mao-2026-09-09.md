# Recuperação offline de captura iOS sem tripé

Caso de 2026-09-09: vídeo de duas voltas, 62,247 s, 3.733 quadros, 3.732 amostras ARKit. Os 30 quadros escolhidos pelo SDK geraram 12 emendas que acionaram suavização de risco. Uma seleção offline de **60 quadros da segunda volta**, com o mesmo montador `videoRefine`, reduziu esse contador para 3 e melhorou a uniformidade visual. Ainda há paralaxe e contornos deformados; não equivale a uma captura com tripé.

## Evidência e limites

Fonte nativa: commit `9440e9f5be66886e9fed0588d5f8c5a342e0145d`. A reprodução dos JPEGs originais no Mac teve os mesmos diagnósticos do iPhone, com erro médio por canal de 0,000047/255. Fonte de vídeo SHA-256: `415b4e744a420950ab9565213cbbf799413b2a2d6d3f225e60bddaed7903aabb`.

| Seleção | Pares refinados | Emendas com suavização de risco | Custo médio de emenda |
|---|---:|---:|---:|
| Original: primeira volta / 30 | 24/30 | 12 | 23,03 |
| Offline: primeira volta / 30 | 23/30 | 12 | 23,26 |
| Offline: primeira volta / 60 | 57/60 | 3 | 15,50 |
| Offline: segunda volta / 30 | 23/30 | 11 | 19,22 |
| Offline: segunda volta / 60 | 58/60 | 3 | 13,33 |

Os contadores não são notas de qualidade visual comparáveis por porcentagem. Mais quadros mudam a sobreposição e a quantidade de pares. Tempos individuais no Mac, com OpenCV 4.13, ficaram entre 3,08 e 8,29 s; a seleção escolhida levou 5,94 s. Não é um benchmark de desempenho no iPhone.

O limite nativo de distância à origem (`currentTranslationMeters <= 0.18`) excluiu todos os 54 registros do setor 25 da segunda volta, entre 18,76 e 19,08 cm. Por isso, ela poderia preencher no máximo 29 setores, antes mesmo da limitação de cadência e do controle de progresso. `bestCoherentLapCandidates` prioriza cobertura em relação à pontuação. A primeira volta, com 30 setores, venceu apesar de uma variação vertical muito maior. Isso é uma limitação concreta a testar na política de seleção; não justifica liberar indiscriminadamente quadros com translação alta.

No vídeo original, a maior distância estimada à origem foi 19,08 cm e a inclinação variou entre −16,73° e −0,48°. A segunda volta teve inclinação entre −5,12° e −1,97°. Poses são estimativas do ARKit, não medições físicas independentes.

O protótipo de projeção por K/R melhorou algumas linhas, mas manteve duplicações. Graph cut sobre essa projeção criou manchas de luminosidade e contornos em degraus. A malha experimental antiga quase não mudou o resultado (erro médio por canal 0,00043/255). Nenhuma dessas opções foi promovida ao SDK.

## Ferramentas

`tool/handheld_benchmark.py` é um laboratório específico para este formato iOS:

- Entrada: MP4 nativo 1920×1440 com apresentação girada para 1440×1920; timeline JSON em lista; PTS extraídos com ffprobe.
- Usa os índices reais de decodificação e PTS, com a quantização CoreMedia a 1/600 s; não estima timestamps por FPS nem interpola poses.
- Examina um quadro a cada três, decodifica em fluxo com memória limitada e extrai seleções de 30/60 imagens de cada uma das duas voltas.
- Preserva K/R e exige ordem temporal dentro da volta. Não é um otimizador global de trajetória; o fechamento continua sujeito a paralaxe.
- Cache de perfil só é reutilizado quando vídeo, timeline e PTS têm hashes iguais. Perfis antigos sem hashes exigem uma pasta de saída nova.
- Os quadros novos são JPEGs qualidade 96. Nitidez offline não tem a mesma escala da métrica nativa.
- A timeline gerada é de laboratório: não substituir diretamente o registro de uma captura no app.

Reprodução, com Python contendo NumPy e OpenCV (execução de referência: Python 3.10.13 / cv2 4.8.1):

```sh
ffprobe -v error -select_streams v:0 -show_frames \
  -show_entries frame=best_effort_timestamp_time -of json \
  "$CAPTURE_DIR/giro360_capture.mp4" > "$PTS_JSON"
python3 tool/handheld_benchmark.py prepare \
  --source "$CAPTURE_DIR" --pts "$PTS_JSON" --output "$EXPERIMENT_DIR"
python3 tool/handheld_benchmark.py native \
  --timeline "$EXPERIMENT_DIR/lap2_60/timeline.json" \
  --library "$STITCHER_DYLIB" --mode 4 \
  --output "$EXPERIMENT_DIR/lap2_60/panorama_videoRefine.jpg"
```

As variáveis acima são caminhos escolhidos pelo operador. Use uma pasta de saída fora dos originais e uma biblioteca macOS compilada da fonte canônica, com OpenCV compatível e símbolo `giro360_stitch_with_options_v2`. Código 20 é a saída guiada esperada nesta execução. Não versionar a biblioteca ou dados pessoais da captura.

`tool/calibrated_cylinder.py` é uma projeção experimental separada. Considera JPEG girado 90° horário, K nativo em paisagem, matriz ARKit câmera→mundo em ordem de linhas e eixos câmera direita/cima/trás. Não compensa translação ou profundidade. Não tem aprovação de qualidade de produção.

```sh
python3 tool/calibrated_cylinder.py \
  --timeline "$EXPERIMENT_DIR/lap2_60/timeline.json" \
  --output "$EXPERIMENT_DIR/lap2_60/calibrated_feather.jpg" --seam feather
```

## Próximas mudanças justificadas

Preservar alternativas das voltas; avaliar deslocamento relativo de vizinhos e fechamento; testar 60 quadros/densidade adaptativa no aparelho; aguardar nível estável antes de contar a rotação; validar projeção por calibração/orientação completas; pedir recaptura localizada quando a geometria não tiver suporte. O caso reforça os aprendizados do WalkIn sobre continuidade e aprovação visual, sem exigir seu pipeline de Gaussian Splatting.

Este trabalho adiciona ferramentas e documentação. Não altera captura, montagem móvel, versão do pacote ou consumidores. Os arquivos brutos e as imagens de avaliação permanecem fora do Git.
