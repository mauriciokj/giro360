# Experimento de geometria e COLMAP

Data local: 26 de agosto de 2026. A sessão foi salva em 27 de agosto em UTC.

## Objetivo

Entender por que o panorama Android gravado sem tripé ainda apresenta tremor,
duplicações e emendas instáveis, e testar as primeiras etapas do roadmap sobre
uma única entrada fixa. Reutilizar o mesmo vídeo elimina a variação causada por
novas capturas e permite comparar os algoritmos diretamente.

## Entrada e calibração

- 60 quadros cobrindo duas voltas.
- Imagens JPEG em retrato, 1080 x 1920.
- Intrínsecos informados pelo ARCore em paisagem, 1920 x 1080.
- Matriz original: `fx=1519,86035`, `fy=1526,47485`, `cx=954,78796`,
  `cy=549,03070`.
- Matriz rotacionada para os JPEGs: `fx=1526,47485`, `fy=1519,86035`,
  `cx=529,96930`, `cy=954,78796`.

A primeira execução do COLMAP utilizou a matriz na orientação errada e foi
descartada. O benchmark e as execuções seguintes detectam essa rotação e
preservam os intrínsecos corrigidos.

## Matching e modelos geométricos

Todos os 60 pares consecutivos, incluindo o fechamento da volta, foram
avaliados com ORB/SIFT, RANSAC/USAC MAGSAC e os modelos de homografia,
fundamental e essencial.

| Configuração | Matches medianos | Inliers H | Cobertura H | Erro H mediano | Classificação H / epipolar / incerta |
| --- | ---: | ---: | ---: | ---: | ---: |
| ORB + RANSAC | 1113,5 | 71,5% | 58,3% | 1,500 px | 29 / 24 / 7 |
| ORB + USAC | 1113,5 | 73,9% | 58,3% | 1,492 px | 32 / 28 / 0 |
| SIFT + RANSAC | 280,5 | 61,4% | 66,7% | 1,242 px | 15 / 35 / 10 |
| SIFT + USAC | 280,5 | 63,0% | 66,7% | 1,281 px | 20 / 38 / 2 |

Os pares mais críticos no teste SIFT + USAC foram `9->10`, `10->11`,
`47->48`, `7->8`, `35->36` e `21->22`. Eles incluem objetos próximos, como
porta, suporte, cadeira, monitores e prateleira. Isso reforça o diagnóstico de
paralaxe: em 38 pares a geometria epipolar explicou melhor a transformação.

## SfM e Bundle Adjustment

O SfM incremental registrou somente 13 dos 60 quadros e não é adequado como
referência para esta sequência circular. O mapeamento global do COLMAP registrou
todos os 60 quadros:

- 2169 pontos 3D;
- 10658 observações;
- comprimento médio das trilhas: 4,914;
- 177,63 observações por imagem;
- erro médio de reprojeção: 0,943 px;
- intrínsecos mantidos fixos após a correção da orientação.

Apesar do bom erro de reprojeção, as poses cruas apresentaram pitch com amplitude
de 23,81 graus, enquanto a telemetria variou apenas 2,10 graus, além de alguns
saltos de posição. O panorama gerado com as poses cruas ficou claramente pior.

## Fusão com ARCore

Foram geradas duas variantes conservadoras:

- `yaw_fused`: correções visuais suavizadas e limitadas a 4 graus no yaw,
  mantendo o pitch do ARCore;
- `fused`: a mesma correção de yaw e até 1 grau de correção visual no pitch.

| Variante | Refinamentos aceitos | Emendas fracas | Custo médio | Pior custo |
| --- | ---: | ---: | ---: | ---: |
| Linha de base | 18 | 11 | 18,12 | 47,35 |
| COLMAP yaw_fused | 23 | 11 | 17,82 | 59,28 |
| COLMAP fused | 23 | 9 | 17,70 | 59,20 |

A variante `fused` é a melhor das opções COLMAP nas métricas médias, porém o
pior custo aumentou. Ela ainda precisa vencer a comparação visual antes de ser
promovida para o SDK.

## SIFT e ALIKED com LightGlue

Para manter o teste viável em CPU, LightGlue foi aplicado a 180 pares: os três
vizinhos de cada quadro e os pares de fechamento da volta. O primeiro ensaio
exaustivo foi interrompido porque crescia quadraticamente e pressionava a
memória do computador sem acrescentar pares relevantes ao vídeo ordenado.

| Reconstrução global | Quadros | Pontos 3D | Observações | Trilha média | Erro de reprojeção |
| --- | ---: | ---: | ---: | ---: | ---: |
| SIFT clássico exaustivo | 60 | 2169 | 10658 | 4,914 | 0,943 px |
| SIFT + LightGlue sequencial | 60 | 3373 | 18304 | 5,427 | 1,064 px |
| ALIKED + LightGlue sequencial | 60 | 3023 | 17276 | 5,715 | 1,169 px |

SIFT + LightGlue produziu em média 451,6 matches e 438,5 inliers nos 180
pares. ALIKED + LightGlue produziu 433,7 matches e 430,5 inliers. O ALIKED
extraiu em média 947 pontos por imagem, variando de 105 em paredes lisas a 2048
em regiões texturizadas.

| Pose global | RMSE yaw | Erro máximo yaw | RMSE pitch cru | Maior salto relativo |
| --- | ---: | ---: | ---: | ---: |
| SIFT clássico | 3,93 graus | 11,12 graus | 3,13 graus | 128,00 |
| SIFT + LightGlue | 3,13 graus | 6,45 graus | 1,14 graus | 19,78 |
| ALIKED + LightGlue | 3,03 graus | 6,77 graus | 1,14 graus | 3,83 |

Os matchers modernos reduziram bastante as ambiguidades das poses. ALIKED
produziu a trajetória mais regular, embora com erro de reprojeção local maior.

| Panorama | Emendas fracas | Custo médio | Pior custo |
| --- | ---: | ---: | ---: |
| SIFT + LightGlue yaw_fused | 10 | 17,64 | 59,32 |
| SIFT + LightGlue fused | 9 | 17,89 | 58,80 |
| ALIKED + LightGlue yaw_fused | 10 | 17,81 | 59,48 |
| ALIKED + LightGlue fused | 9 | 17,91 | 59,37 |

Na inspeção ampla, nenhuma dessas quatro variantes removeu os fantasmas na
cadeira, nos monitores e em outros objetos próximos. Elas foram publicadas como
testes 27 a 30 no Visão360 para avaliação detalhada. Até essa avaliação, a linha
de base continua sendo a referência visual.

## Priors ARCore e GraphCut

As 60 posições do `cameraTransform` do ARCore foram importadas como priors
cartesianos no COLMAP com desvio padrão de 0,10 m. O refinamento preservou os 60
quadros e produziu:

- 2743 pontos 3D e 15967 observações;
- trilha média de 5,821;
- erro médio de reprojeção de 1,231 px;
- RMSE de yaw de 3,10 graus e erro máximo de 7,58 graus;
- RMSE de pitch cru de 1,06 grau;
- passo relativo mediano de 0,008 e máximo de 0,071.

O prior removeu os saltos da trajetória, mas não eliminou os fantasmas do
panorama. A versão `fused` foi publicada como teste 31. Em seguida, a mesma
timeline foi processada pelo motor GraphCut/multibanda: GraphCut foi aplicado em
40 emendas e multibanda em 5. O teste 32 reduziu algumas misturas suaves, mas
introduziu cortes geométricos mais evidentes e não substitui a referência.

## Ferramentas reproduzíveis

- `tool/benchmark_geometry.py`: executa a matriz de features, estimadores e
  modelos, exportando JSON, CSV e visualizações dos piores pares.
- `tool/colmap_timeline.py`: converte rotações globais do COLMAP para a timeline
  e gera os modos `raw`, `yaw_fused` e `fused`.
- `tool/import_pose_priors.py`: importa as posições cartesianas do ARCore na
  tabela de priors do COLMAP, com incerteza configurável.
- `tool/extract_sharp_lap.py`: percorre todos os frames do vídeo e seleciona,
  por ângulo e ordem temporal, os quadros mais nítidos de uma única volta.
- `tool/depth_anything.swift`: executa o modelo Core ML fixo da Apple para
  diagnósticos rápidos da faixa central.
- `tool/depth_anything_v2.py`: executa Depth Anything V2 no quadro retrato
  completo e salva profundidade relativa, máscara normalizada e prévia.
- `tool/depth_pair_diagnostics.py`: cruza matches geométricos e profundidade nos
  seis pares críticos.

## Seleção por nitidez e profundidade

A gravação original contém 994 frames. Em vez de capturar novamente, cada volta
foi reamostrada em 60 posições, procurando um frame mais nítido perto do ângulo
alvo sem quebrar a ordem temporal.

| Seleção | Nitidez mediana | Nitidez mínima | Nitidez máxima |
| --- | ---: | ---: | ---: |
| Volta 1 original | 54,52 | 11,74 | 554,80 |
| Volta 1 otimizada | 75,64 | 14,34 | 701,24 |
| Volta 2 original | 56,79 | 8,57 | 632,81 |
| Volta 2 otimizada | 81,44 | 10,91 | 813,68 |

Os testes 34 e 35 ficaram mais definidos, mas mantiveram duplicações nos mesmos
objetos próximos. Isso separa o desfoque de movimento do ghosting geométrico.

Depth Anything V2 Small foi então aplicado aos seis pares críticos. Os maiores
conflitos relativos apareceram em `10->11` e `21->22`; este último tem pouca
textura e profundidade pouco confiável por causa da TV escura. O teste foi
ampliado para os 60 quadros da volta 1 otimizada.

O teste 36 usa uma build experimental que penaliza bordas de profundidade ao
escolher a coluna da seam. Todos os 60 mapas foram carregados. O custo médio da
seam passou de 17,56 para 23,52 e a posição média mudou, produzindo diferenças
em 2,7% dos pixels. O candidato está publicado no Visão360 contra o teste 34.
Essa versão não está habilitada no SDK normal e não altera o pipeline do iOS.

Os testes 37 e 38 trocaram a coluna reta por uma seam dinâmica por linha,
calculada com cor, gradiente e bordas de profundidade. O teste 37 permitiu um
caminho amplo e alterou 20,29% dos pixels; o teste 38 limitou o caminho a um
corredor de 52 px e reduziu o blend para 18 px, mas ainda alterou 14,14% dos
pixels. O percentil 99 das bordas horizontais subiu de 18 no teste 36 para 21
no teste 38. Visualmente, a seam passou a contornar detalhes, mas criou cortes
serrilhados na estante, TV e cadeira. O teste 34 continua sendo a melhor
referência.

O resultado é útil porque elimina uma hipótese: escolher melhor onde cortar
não corrige duas projeções que discordam por paralaxe. A correção precisa agir
na geometria local antes da composição. A seam dinâmica permanece disponível
somente por flag de compilação experimental e não altera o SDK normal.

## Próximos testes

1. Implementar warping local orientado por profundidade antes da composição.
2. Comparar uma transformação por camadas de profundidade com uma malha local.
3. Manter o teste 34 como referência visual e quantitativa.
4. Repetir a matriz no M3ISR para medir separadamente rotação pura e translação.

No momento do teste havia cerca de 8 GB livres. Downloads de datasets e modelos
grandes ficam adiados até haver espaço suficiente, mas os testes locais de
geometria e COLMAP continuam reproduzíveis sem esses recursos.
