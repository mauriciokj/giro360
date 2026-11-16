# Seleção automática depois da captura — SDK 0.0.4

Ao concluir a rotação, o SDK salva o vídeo e a telemetria, examina as imagens gravadas, escolhe uma volta coerente e extrai os quadros para o montador. O padrão passa a 60 imagens. O consumidor continua usando `Giro360CaptureController.start()`; não precisa acionar uma segunda montagem nem enviar dados para um servidor.

`finalizing` permanece ativo durante análise e extração, com mensagens de progresso. O vídeo e a telemetria são preservados em caso de falha. Cancelar interrompe a leitura/extração; callbacks de uma geração anterior não alteram uma captura nova. O SDK não permite iniciar outra captura enquanto o coordenador está ocupado.

## Seleção e arquivos

- Decodificação sequencial com AVFoundation, descartando cada imagem após uso. Analisa um quadro a cada três, com miniaturas de 360×480 e variância do Laplaciano do OpenCV.
- Usa PTS reais e a quantização a 1/600 s da gravação. Confere a identidade temporal novamente na extração; não associa pixels de um vizinho à pose selecionada.
- Preserva candidatos com rastreamento normal mesmo além de 18 cm da origem. Compara cobertura, inclinação, movimento relativo entre vizinhos e fechamento circular. Distância à origem continua disponível como diagnóstico.
- Avalia cada volta separadamente. Dentro de um setor, considera ângulo, velocidade, inclinação e nitidez. Não mistura livremente pontos de vista de voltas distintas.
- As imagens ficam em `selected_frames/video_NNN.jpg`, com caminhos absolutos no resultado da API. Consumidores que persistem nomes devem guardar o caminho relativo completo, incluindo `selected_frames/`.
- `selected_frames/giro360_selection.json` preserva versão do seletor, tempos, scores por volta, índices de vídeo, PTS, calibração e poses. `Giro360CaptureStatus.processingDiagnostics` transporta o resumo ao Dart.
- Timelines novas incluem timestamps de câmera e de calendário. No replay de dados antigos sem esses campos, o SDK não inventa horários absolutos.

Não há certificado automático de qualidade: paralaxe, desfoque, oclusões e falta de cobertura continuam podendo produzir defeitos. A projeção calibrada, graph cut e malha local do laboratório não foram habilitadas no montador.

Desde a versão 0.0.5, se os melhores candidatos individuais de uma volta não
estiverem em ordem temporal, o seletor procura uma sequência ordenada entre as
alternativas dos mesmos setores. Quando a seleção original já está em ordem,
ela permanece inalterada. O diagnóstico registra `selectionVersion: 2`.

O status da captura também pode informar `recentTranslationMeters`: a maior
distância entre posições estimadas pelo ARKit numa janela de até dois segundos.
Esse indicador é independente da distância à origem, expira ao perder o
rastreamento e não substitui a inspeção visual nem mede profundidade da cena.

## Validação realizada

A captura na mão de 9 de setembro foi processada por Swift/AVFoundation no Mac e no iPhone 16e. Ambos escolheram os mesmos 60 quadros da segunda volta, também iguais à seleção Python aprovada visualmente. A diferença média por canal entre os panoramas Swift do Mac e do iPhone foi 0,00131/255. Isso não significa JPEG idêntico ao laboratório Python, pois a extração/compressão são diferentes.

Na primeira execução no iPhone: 9,514 s para selecionar/extrair, 7,363 s para montar, total 16,877 s. É uma medição de um vídeo salvo; não inclui o tempo de gravação e não constitui promessa de latência para qualquer aparelho ou cena. A referência com tripé foi testada no Mac e manteve três emendas que acionam a suavização de risco.

Os testes Swift cobrem seleção de uma volta coerente deslocada da origem, cobertura, setor zero no fechamento, deslocamento circular, ordem temporal, entrada vazia, timestamps duplicados, proteção dos originais e cancelamento. Os testes Dart cobrem preservação dos metadados, estado do controlador e isolamento entre tentativas.

## Repetir um vídeo salvo

`Giro360NativeCaptureService.selectRecordedVideo()` expõe o mesmo seletor para recuperação e validação. Recebe vídeo, timeline, uma pasta de saída que ainda não existe e, opcionalmente, número de setores/voltas. Retorna `frames` e `diagnostics`; a chamada não monta o panorama sozinha. Para o fluxo comum, use o controlador, que já encadeia a montagem automaticamente.

O exemplo inclui `lib/replay_validation.dart`, um ponto de entrada exclusivo para teste. Antes de abri-lo, copie o MP4 e a timeline para `Documents/Giro360Validation` no contêiner do exemplo. Ele grava resultados em `Documents/Giro360ValidationResults`, sem alterar os dados de entrada. A aplicação de exemplo normal continua em `lib/main.dart`.

No Mac, compile `tool/replay_post_capture.swift` junto de `Giro360VideoSelection.swift` e `Giro360VideoProcessor.swift`, vinculando a biblioteca do montador compilada para macOS. O comando aceita diretório da captura, nova pasta de saída e número de setores. `tool/test_video_selection.swift` e `tool/test_video_processor.swift` usam as mesmas fontes, sem duplicar o algoritmo de produção.
