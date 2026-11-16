import AVFoundation
import CoreVideo
import Foundation
import ImageIO
import Darwin

@_silgen_name("giro360_frame_quality_bgra")
private func giro360FrameQuality(_ pixels: UnsafePointer<UInt8>, _ width: Int32,
                                _ height: Int32, _ stride: Int32, _ rotation: Int32,
                                _ sharpness: UnsafeMutablePointer<Double>,
                                _ overlap: UnsafeMutablePointer<Double>) -> Int32

final class Giro360ProcessingCancellation {
  private let lock = NSLock()
  private var cancelled = false
  func cancel() { lock.lock(); cancelled = true; lock.unlock() }
  func check() throws {
    lock.lock(); let value = cancelled; lock.unlock()
    if value { throw Giro360VideoProcessor.error("Processamento cancelado.", code: -999) }
  }
}

/// The same AVFoundation/OpenCV pipeline runs on iOS and in the macOS replay.
/// Only metadata/thumbnails survive each decoded frame; full images are streamed.
struct Giro360VideoProcessor {
  static func error(_ text: String, code: Int = 10) -> NSError {
    NSError(domain: "Giro360VideoSelection", code: code, userInfo: [NSLocalizedDescriptionKey: text])
  }

  static func process(video: URL, timeline: [[String: Any]], output: URL,
                      bins: Int, laps: Int, direction: Double? = nil,
                      cancellation: Giro360ProcessingCancellation = Giro360ProcessingCancellation(),
                      progress: (String) -> Void = { _ in }) throws -> [String: Any] {
    let started = Date()
    guard (24...90).contains(bins), (1...3).contains(laps), !timeline.isEmpty else {
      throw error("Configuração ou telemetria inválida.")
    }
    // Never replace originals or a previous successful processing attempt.
    guard !FileManager.default.fileExists(atPath: output.path) else {
      throw error("Escolha uma nova pasta para os quadros processados.")
    }
    var previousTime = -Double.infinity
    for sample in timeline {
      guard let time = sample["videoTimeSeconds"] as? Double, time.isFinite, time >= 0,
            time > previousTime,
            let transform = sample["cameraTransform"] as? [Double], transform.count == 16,
            let intrinsics = sample["cameraIntrinsics"] as? [Double], intrinsics.count == 9,
            (transform + intrinsics).allSatisfy({ $0.isFinite }),
            ["relativeYawRadians", "pitchRadians", "rollRadians", "translationMeters", "angularSpeedRadiansPerSecond"].allSatisfy({
              (sample[$0] as? Double)?.isFinite == true
            }) else { throw error("A telemetria não contém poses e horários válidos.") }
      previousTime = time
    }
    let inferredYaw = timeline.compactMap { $0["relativeYawRadians"] as? Double }.max { abs($0) < abs($1) }!
    let sign = direction ?? (inferredYaw < 0 ? -1.0 : 1.0)
    guard sign == 1 || sign == -1 else { throw error("Direção inválida.") }
    let asset = AVURLAsset(url: video)
    guard let track = asset.tracks(withMediaType: .video).first else { throw error("Vídeo sem imagens.") }
    let transform = track.preferredTransform
    let quarterTurns = [0, 1, 2, 3].min { a, b in
      abs(normalized(atan2(transform.b, transform.a) - Double(a) * .pi / 2)) <
      abs(normalized(atan2(transform.b, transform.a) - Double(b) * .pi / 2))
    }!
    guard abs(normalized(atan2(transform.b, transform.a) - Double(quarterTurns) * .pi / 2)) < 0.01,
          transform.a * transform.d - transform.b * transform.c > 0 else {
      throw error("A orientação deste vídeo não é suportada.")
    }
    let reader = try AVAssetReader(asset: asset)
    let decoded = AVAssetReaderTrackOutput(track: track, outputSettings: [
      kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
    ])
    decoded.alwaysCopiesSampleData = false
    guard reader.canAdd(decoded) else { throw error("Não foi possível abrir o vídeo.") }
    reader.add(decoded)
    guard reader.startReading() else { throw reader.error ?? error("Falha ao decodificar o vídeo.") }
    defer { if reader.status == .reading { reader.cancelReading() } }
    let encodedTimes = timeline.map { Double(Int64(($0["videoTimeSeconds"] as! Double) * 600)) / 600 }
    try cancellation.check()
    try FileManager.default.createDirectory(at: output.deletingLastPathComponent(), withIntermediateDirectories: true)
    guard mkdir(output.path, 0o700) == 0 else {
      throw error("A pasta de saída já existe ou não pode ser criada.")
    }
    var completed = false
    defer {
      // mkdir atomically claimed this directory; cleanup cannot delete another run.
      if !completed { try? FileManager.default.removeItem(at: output) }
    }
    var rows: [Giro360ProfiledFrame] = []
    var frameIndex = 0, cursor = 0
    var lastPTS = -Double.infinity
    var maxTimeError = 0.0
    progress("Analisando nitidez e movimento das voltas…")
    while true {
      try cancellation.check()
      var ended = false
      try autoreleasepool {
        guard let buffer = decoded.copyNextSampleBuffer() else { ended = true; return }
        let pts = CMTimeGetSeconds(CMSampleBufferGetPresentationTimeStamp(buffer))
        guard pts.isFinite, pts > lastPTS else { throw error("O vídeo tem horários fora de ordem.") }
        lastPTS = pts
        defer { frameIndex += 1 }
        guard frameIndex % 3 == 0 else { return }
        while cursor + 1 < encodedTimes.count && encodedTimes[cursor + 1] <= pts { cursor += 1 }
        let nearest = [cursor, min(cursor + 1, encodedTimes.count - 1)].min {
          abs(encodedTimes[$0] - pts) < abs(encodedTimes[$1] - pts)
        }!
        let timeError = abs(encodedTimes[nearest] - pts)
        // Match an actual encoded PTS, never a neighbouring frame or an interpolated pose.
        guard timeError < 1.0 / 1200 else { return }
        let sample = timeline[nearest]
        guard sample["recorded"] as? Bool == true, sample["trackingState"] as? String == "normal",
              let pixels = CMSampleBufferGetImageBuffer(buffer) else { return }
        CVPixelBufferLockBaseAddress(pixels, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixels, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pixels) else { throw error("Quadro sem pixels.") }
        var sharpness = 0.0, overlap = 0.0
        let result = giro360FrameQuality(base.assumingMemoryBound(to: UInt8.self),
          Int32(CVPixelBufferGetWidth(pixels)), Int32(CVPixelBufferGetHeight(pixels)),
          Int32(CVPixelBufferGetBytesPerRow(pixels)), Int32(quarterTurns), &sharpness, &overlap)
        guard result == 0, sharpness.isFinite, overlap.isFinite else { throw error("Falha na análise de nitidez.") }
        maxTimeError = max(maxTimeError, timeError)
        rows.append(Giro360ProfiledFrame(sample: sample, frameIndex: frameIndex, pts: pts,
          progress: sign * (sample["relativeYawRadians"] as! Double), sharpness: sharpness, overlapSharpness: overlap))
      }
      if ended { break }
      if frameIndex % 300 == 0 {
        let percent = min(100, Int(lastPTS / max(previousTime, 0.001) * 100))
        progress("Analisando as voltas: \(percent)%")
      }
    }
    guard reader.status == .completed else { throw reader.error ?? error("A leitura do vídeo foi interrompida.") }
    let profileElapsed = Date().timeIntervalSince(started)
    try cancellation.check()
    let selections = Giro360VideoSelector.select(rows, bins: bins, laps: laps)
    guard let selected = selections.first else {
      throw error("O vídeo foi preservado, mas faltam imagens contínuas para montar. Faça uma nova captura mais devagar.")
    }
    progress("Selecionando a volta \(selected.lapIndex + 1) e preparando \(selected.frames.count) imagens…")
    let generator = AVAssetImageGenerator(asset: asset)
    generator.appliesPreferredTrackTransform = true
    generator.requestedTimeToleranceBefore = .zero
    generator.requestedTimeToleranceAfter = .zero
    var frames = selected.frames
    for i in frames.indices {
      try cancellation.check()
      try autoreleasepool {
        let pts = frames[i]["ptsSeconds"] as! Double
        let time = CMTime(value: Int64((pts * 600).rounded()), timescale: 600)
        var actualTime = CMTime.zero
        let image = try generator.copyCGImage(at: time, actualTime: &actualTime)
        guard abs(CMTimeGetSeconds(actualTime) - pts) < 1.0 / 1200 else {
          throw error("A imagem extraída não corresponde à pose gravada.")
        }
        let bin = frames[i]["binIndex"] as! Int
        let path = output.appendingPathComponent(String(format: "video_%03d.jpg", bin))
        guard let destination = CGImageDestinationCreateWithURL(path as CFURL, "public.jpeg" as CFString, 1, nil) else {
          throw error("Não foi possível salvar os quadros.")
        }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.96] as CFDictionary)
        guard CGImageDestinationFinalize(destination) else { throw error("Falha ao gravar a imagem selecionada.") }
        frames[i]["filePath"] = path.path
        if let unix = frames[i]["captureUnixTimeSeconds"] as? Double {
          let formatter = ISO8601DateFormatter()
          formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
          frames[i]["capturedAt"] = formatter.string(from: Date(timeIntervalSince1970: unix))
        }
      }
      if (i + 1) % 10 == 0 { progress("Preparando imagens: \(i + 1)/\(frames.count)") }
    }
    try cancellation.check()
    let result: [String: Any] = [
      "selectionVersion": 2, "selectedLap": selected.lapIndex + 1, "binCount": bins,
      "frames": frames, "profiledFrameCount": rows.count, "decodedFrameCount": frameIndex,
      "profileElapsedSeconds": profileElapsed, "elapsedSeconds": Date().timeIntervalSince(started),
      "maxMatchedTimestampErrorSeconds": maxTimeError, "direction": sign > 0 ? "right" : "left",
      "lapScores": selections.map { ["lap": $0.lapIndex + 1, "frames": $0.frames.count,
        "score": $0.score, "pitchSpanDegrees": $0.pitchSpan * 180 / .pi,
        "maxAdjacentDistanceMeters": $0.maxAdjacentDistance] as [String: Any] }
    ]
    let data = try JSONSerialization.data(withJSONObject: result, options: [.prettyPrinted, .sortedKeys])
    try data.write(to: output.appendingPathComponent("giro360_selection.json"), options: .atomic)
    completed = true
    return result
  }

  private static func normalized(_ value: Double) -> Double {
    atan2(sin(value), cos(value))
  }
}
