# Roadmap de visão computacional

Este documento organiza os estudos do Giro360 entre a captura de um vídeo e a
geração de um panorama cilíndrico geometricamente consistente. A ordem prioriza
os problemas observados nos testes reais, especialmente paralaxe, conteúdo
duplicado, setores ausentes e fechamento inconsistente da volta.

## Método de trabalho

Cada experimento deve manter os arquivos de entrada, registrar o algoritmo e os
parâmetros usados e produzir métricas comparáveis. Uma técnica só substitui a
anterior quando melhora o conjunto de testes sem introduzir regressões claras.

Métricas mínimas:

- cobertura espacial e quantidade de matches;
- inlier ratio e erro de reprojeção;
- erro de rotação e translação entre voltas;
- erro visual de loop closure;
- variação dos intrínsecos e do ponto principal;
- setores medidos e reconstruídos;
- falhas de seam, ghosting e conteúdo duplicado.

## Fase 1: linha de base geométrica

1. Calibração da câmera: intrínsecos, distorção, campo de visão e estabilidade.
2. Loop closure: consistência de pose e imagem entre posições equivalentes das
   duas voltas.
3. Feature matching: ORB como linha de base, seguido por SIFT e LightGlue.
4. Filtragem: RANSAC/USAC, inlier ratio e cobertura espacial dos inliers.
5. Seleção de modelo: comparar homografia, matriz fundamental e matriz
   essencial, principalmente em cenas com paralaxe.
6. Stitching: warping, seam finding, exposição e multi-band blending.

## Fase 2: otimização global

1. Estimação de poses e triangulação.
2. SfM incremental e global.
3. Bundle Adjustment.
4. Correção global de drift e fechamento da volta.
5. COLMAP como referência offline para as poses estimadas no aparelho.
6. Fusão visual-inercial com ARKit, ARCore e IMU.

## Fase 3: profundidade e reconstrução moderna

1. Profundidade monocular e multi-view com mapas de confiança.
2. Seams orientados por profundidade para reduzir ghosting por paralaxe.
3. DUSt3R, MASt3R, VGGT e variantes panorâmicas.
4. 3D Gaussian Splatting e reconstrução pose-free.
5. Validação em datasets como TartanAir, EuRoC, ETH3D, ScanNet e benchmarks
   panorâmicos compatíveis.

## Experimento atual: calibração e loop closure Android

A primeira implementação registra `cameraCalibration` e `loopClosure` em
`giro360_video_timeline.json` e no status retornado pelo SDK.

`cameraCalibration` inclui:

- resolução da imagem ARCore;
- média de `fx`, `fy`, `cx` e `cy`;
- campos de visão horizontal e vertical calculados;
- variação focal e deslocamento do ponto principal;
- intrínsecos e distorção disponibilizados pelo Camera2;
- dimensões físicas e ativas do sensor quando expostas pelo aparelho.

`loopClosure` compara os mesmos setores nas duas voltas e inclui:

- quantidade de setores comparáveis;
- erros médio e máximo de translação;
- erros médio e máximo de rotação e azimute;
- ORB + RANSAC entre duas observações da mesma direção;
- matches, inliers, inlier ratio, cobertura espacial e erro de reprojeção;
- indicadores separados de confiança da pose e da comparação visual.

Esta fase é diagnóstica. Ela não altera o panorama final. Os resultados vão
orientar se o próximo passo deve ser recalibração, melhoria do matching,
mudança do modelo geométrico ou tratamento de seam.
