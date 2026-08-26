# Experimento Android sem tripé - 2026-08-26

## Objetivo

Avaliar se uma captura manual do Galaxy Tab poderia ser recuperada apenas com
mudanças na seleção de frames e na costura, sem repetir a gravação.

## Diagnóstico da captura

- Vídeo: 38,5 segundos, duas voltas e 1.203 poses analisadas.
- Calibração: estável, sem variação relevante da distância focal observada.
- Translação média da volta escolhida: 11,2 cm.
- Translação máxima da volta escolhida: 16,4 cm.
- Variação vertical aproximada: 17,5 graus de pitch.
- Fechamento visual: 103 inliers de 144 matches, mas cobertura espacial de
  apenas 16,7%, insuficiente para uma correção global confiável.
- O operador ocultou parte do ambiente em setores consecutivos das duas voltas.

## Variantes locais

| Variante | Resultado |
| --- | --- |
| 30 setores, refinamento de vídeo | Referência; muitas junções rígidas falharam |
| 30 setores, GraphCut + multiband | Suavizou o operador, mas deformou persianas e linhas rígidas |
| Stitcher completo por features | Lento e sem ORB suficiente; retornou ao fallback guiado |
| 45 setores | 7 pares refinados; corte circular e junções piores que 60 |
| 60 setores, primeira volta | 16 pares refinados; melhor continuidade do ambiente |
| 60 setores, segunda volta | 12 pares refinados; pior que a primeira volta |
| 60 setores, GraphCut + multiband | Mais seams aplicados, mas novas deformações em objetos rígidos |

## Decisão

Usar no mínimo 60 setores no fluxo AR do Android e manter o seam adaptativo
atual. GraphCut, multiband e o Stitcher global não serão promovidos nesta etapa.
O iOS permanece inalterado.

## Limite do processamento

A variante de 60 setores melhora as ligações que possuem conteúdo sobreposto,
mas não pode remover o operador nem reconstruir o ambiente que ficou atrás
dele. A correção principal para esse caso é de captura: manter a tela voltada
para o usuário e girar o corpo inteiro junto com o aparelho, ou usar tripé com a
lente sobre o eixo e disparo remoto.

## Artefatos locais

- Referência: `android_handheld_2026-08-26/giro360_panorama.jpg`
- Melhor variante: `android_handheld_2026-08-26/lap1_60/panorama_videoRefine.jpg`
- Diagnóstico: `android_handheld_2026-08-26/lap1_60/status_videoRefine.txt`

Os artefatos ficam no workspace do laboratório e não são versionados por causa
do tamanho. Os scripts `tool/extract_timeline_lap.dart` e
`tool/reprocess_timeline.dart` reproduzem a seleção e a costura.
