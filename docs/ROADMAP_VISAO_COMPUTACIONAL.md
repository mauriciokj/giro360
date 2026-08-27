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

## Benchmark planejado: M3ISR

O [M3ISR no Hugging Face](https://huggingface.co/datasets/XinhuiLiu001/M3ISR)
será usado como benchmark sintético controlado. O conjunto contém cenas internas
e externas, seis câmeras sincronizadas, parâmetros intrínsecos e extrínsecos,
profundidade ground truth, segmentação semântica e de instâncias e máscaras de
regiões estáticas e dinâmicas.

O principal objetivo deste benchmark é separar rotação pura de translação da
câmera. Algumas vistas mantêm a mesma posição e variam o `yaw`, permitindo
comparar diretamente o cenário ideal para panorama com casos que introduzem
paralaxe.

### Teste A: rotação com centro óptico fixo

- [ ] Selecionar vistas com a mesma posição e rotações horizontais diferentes.
- [ ] Gerar panoramas com homografia e projeção cilíndrica.
- [ ] Comparar ORB, SIFT e, posteriormente, LightGlue no mesmo conjunto.
- [ ] Medir matches, cobertura espacial, inlier ratio, erro de reprojeção,
  fechamento da volta, seams e ghosting.
- [ ] Confirmar a qualidade máxima esperada quando não existe translação.

Resultado esperado: a homografia deve explicar a maior parte da transformação,
sem conteúdo duplicado provocado por paralaxe. Este teste será a referência
superior de qualidade do pipeline de stitching.

### Teste B: câmera com translação

- [ ] Selecionar pares e sequências com deslocamento conhecido entre câmeras.
- [ ] Executar inicialmente o mesmo pipeline baseado em homografia.
- [ ] Comparar homografia com matriz fundamental, matriz essencial e SfM.
- [ ] Usar a profundidade ground truth para medir o erro por faixa de distância.
- [ ] Avaliar warping e escolha de seam orientados por profundidade.
- [ ] Repetir o teste separando regiões estáticas de objetos dinâmicos pelas
  máscaras fornecidas pelo dataset.

Resultado esperado: identificar os tipos de cena e as distâncias em que a
homografia passa a produzir duplicação, desalinhamento e ghosting, e medir o
ganho obtido com pose relativa, SfM e profundidade.

### Teste C: limite entre homografia e SfM

- [ ] Ordenar pares pela distância entre os centros de câmera.
- [ ] Aumentar progressivamente a translação, mantendo cena, intrínsecos e
  diferença angular tão constantes quanto possível.
- [ ] Registrar a curva de erro de reprojeção, inlier ratio, consistência de
  profundidade e erro visual conforme o deslocamento aumenta.
- [ ] Definir um critério objetivo para o SDK escolher entre homografia e um
  pipeline com geometria 3D.

Cada execução deve salvar a seleção de câmeras, poses ground truth, parâmetros,
panorama produzido, visualização dos matches, mapa de erro e uma linha na tabela
comparativa. Cenas internas e externas devem ser avaliadas separadamente.

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

## Progresso da bateria Android sem tripé

Sessão analisada: 60 quadros selecionados de duas voltas, capturados no Galaxy
Tab sem tripé. O relatório completo está em
[`EXPERIMENTO_GEOMETRIA_COLMAP_2026-08-26.md`](EXPERIMENTO_GEOMETRIA_COLMAP_2026-08-26.md).

- [x] Corrigir os intrínsecos do ARCore para a orientação retrato dos JPEGs.
- [x] Comparar ORB e SIFT em todos os pares consecutivos.
- [x] Comparar RANSAC e USAC MAGSAC.
- [x] Medir homografia, matriz fundamental e matriz essencial.
- [x] Executar SfM incremental como linha de base.
- [x] Executar SfM global e Bundle Adjustment no COLMAP.
- [x] Comparar poses puramente visuais e fusão limitada com a telemetria ARCore.
- [ ] Validar visualmente as variantes `yaw_fused` e `fused` no Visão360.
- [ ] Comparar SIFT com ALIKED/LightGlue mantendo a mesma captura.
- [ ] Usar as poses ARCore como priors no mapeamento global.
- [ ] Testar seam orientado por profundidade nos pares críticos.
- [ ] Executar os testes controlados A, B e C no M3ISR.

Resultado parcial: 38 dos 60 pares foram melhor explicados por geometria
epipolar no melhor teste SIFT + USAC. Uma única homografia não representa bem
a maior parte desta captura com translação. O SfM global registrou os 60 quadros
com erro médio de reprojeção de 0,943 px, mas as poses visuais cruas ainda
contêm ambiguidades. Portanto, o próximo ganho provável está em fusão de poses,
matching mais robusto ou profundidade, e não apenas em trocar o blender.
