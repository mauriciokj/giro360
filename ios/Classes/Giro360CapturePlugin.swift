import ARKit
import AVFoundation
import Flutter
import SceneKit
import UIKit

@_silgen_name("giro360_stitch_link_anchor")
private func giro360StitchLinkAnchor()

public final class Giro360CapturePlugin: NSObject, FlutterPlugin, FlutterStreamHandler {
  private static let channelName = "giro360_capture/methods"
  private static let eventChannelName = "giro360_capture/events"
  private static let viewType = "giro360_capture/preview"

  private let coordinator = Giro360ArkitCaptureCoordinator()
  private var eventSink: FlutterEventSink?
  private var ownsIdleTimer = false
  private var previousIdleTimerState = false

  public static func register(with registrar: FlutterPluginRegistrar) {
    giro360StitchLinkAnchor()
    let plugin = Giro360CapturePlugin()
    let channel = FlutterMethodChannel(
      name: channelName,
      binaryMessenger: registrar.messenger()
    )
    let eventChannel = FlutterEventChannel(
      name: eventChannelName,
      binaryMessenger: registrar.messenger()
    )
    registrar.addMethodCallDelegate(plugin, channel: channel)
    eventChannel.setStreamHandler(plugin)
    registrar.register(
      Giro360ArkitPreviewFactory(coordinator: plugin.coordinator),
      withId: viewType
    )
    plugin.coordinator.statusDidChange = { [weak plugin] status in
      plugin?.publish(status)
    }
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    switch call.method {
    case "isSupported":
      result(ARWorldTrackingConfiguration.isSupported)
    case "startCapture":
      guard let arguments = call.arguments as? [String: Any],
            let directoryPath = arguments["directoryPath"] as? String else {
        result(
          FlutterError(
            code: "invalid_arguments",
            message: "O diretório da captura não foi informado.",
            details: nil
          )
        )
        return
      }
      let binCount = max(24, min(90, arguments["binCount"] as? Int ?? 30))
      let requiredLaps = max(1, min(3, arguments["requiredLaps"] as? Int ?? 2))
      do {
        try coordinator.startCapture(
          directoryPath: directoryPath,
          binCount: binCount,
          requiredLaps: requiredLaps
        )
        result(nil)
      } catch {
        result(
          FlutterError(
            code: "arkit_start_failed",
            message: error.localizedDescription,
            details: nil
          )
        )
      }
    case "status":
      result(coordinator.status())
    case "cancelCapture":
      coordinator.cancelCapture()
      result(nil)
    default:
      result(FlutterMethodNotImplemented)
    }
  }

  public func onListen(
    withArguments arguments: Any?,
    eventSink events: @escaping FlutterEventSink
  ) -> FlutterError? {
    eventSink = events
    publish(coordinator.status())
    return nil
  }

  public func onCancel(withArguments arguments: Any?) -> FlutterError? {
    eventSink = nil
    return nil
  }

  private func publish(_ status: [String: Any]) {
    DispatchQueue.main.async { [weak self] in
      guard let self else { return }
      updateIdleTimer(status: status)
      eventSink?(status)
    }
  }

  private func updateIdleTimer(status: [String: Any]) {
    let captureActive = status["running"] as? Bool == true
      || status["finishing"] as? Bool == true
    if captureActive, !ownsIdleTimer {
      previousIdleTimerState = UIApplication.shared.isIdleTimerDisabled
      UIApplication.shared.isIdleTimerDisabled = true
      ownsIdleTimer = true
    } else if !captureActive, ownsIdleTimer {
      UIApplication.shared.isIdleTimerDisabled = previousIdleTimerState
      ownsIdleTimer = false
    }
  }
}

private final class Giro360ArkitPreviewFactory: NSObject, FlutterPlatformViewFactory {
  private let coordinator: Giro360ArkitCaptureCoordinator

  init(coordinator: Giro360ArkitCaptureCoordinator) {
    self.coordinator = coordinator
    super.init()
  }

  func create(
    withFrame frame: CGRect,
    viewIdentifier viewId: Int64,
    arguments args: Any?
  ) -> FlutterPlatformView {
    Giro360ArkitPreviewView(frame: frame, coordinator: coordinator)
  }
}

private final class Giro360ArkitPreviewView: NSObject, FlutterPlatformView {
  private let sceneView: ARSCNView

  init(frame: CGRect, coordinator: Giro360ArkitCaptureCoordinator) {
    sceneView = ARSCNView(frame: frame)
    sceneView.session = coordinator.session
    sceneView.scene = SCNScene()
    sceneView.automaticallyUpdatesLighting = false
    sceneView.contentMode = .scaleAspectFit
    super.init()
  }

  func view() -> UIView {
    sceneView
  }
}

private struct Giro360ArkitFrameCandidate {
  let binIndex: Int
  let lapIndex: Int
  let filePath: String
  let targetYaw: Double
  let relativeYaw: Double
  let pitch: Double
  let roll: Double
  let translationMeters: Double
  let qualityScore: Double
  let sharpnessScore: Double
  let angularSpeed: Double
  let centerError: Double
  let trackingState: String
  let capturedAt: String
  let frameTimestamp: Double
  let videoTime: Double
  let intrinsics: [Double]
  let cameraTransform: [Double]

  var flutterValue: [String: Any] {
    [
      "binIndex": binIndex,
      "lapIndex": lapIndex,
      "filePath": filePath,
      "targetYawRadians": targetYaw,
      "relativeYawRadians": relativeYaw,
      "pitchRadians": pitch,
      "rollRadians": roll,
      "translationMeters": translationMeters,
      "qualityScore": qualityScore,
      "sharpnessScore": sharpnessScore,
      "angularSpeedRadiansPerSecond": angularSpeed,
      "centerErrorRadians": centerError,
      "trackingState": trackingState,
      "capturedAt": capturedAt,
      "frameTimestampSeconds": frameTimestamp,
      "videoTimeSeconds": videoTime,
      "cameraIntrinsics": intrinsics,
      "cameraTransform": cameraTransform,
    ]
  }
}

private final class Giro360ArkitCaptureCoordinator: NSObject, ARSessionDelegate {
  let session = ARSession()
  var statusDidChange: (([String: Any]) -> Void)?

  private let stateQueue = DispatchQueue(label: "giro360.arkit.capture.state")
  private let imageQueue = DispatchQueue(label: "giro360.arkit.capture.images")
  private var directoryPath = ""
  private var binCount = 60
  private var requiredLaps = 2
  private var running = false
  private var finishing = false
  private var complete = false
  private var failed = false
  private var message = "Pronto para iniciar."
  private var trackingState = "not_available"

  private var firstFrameTimestamp: TimeInterval?
  private var firstFrameDate: Date?
  private var initialPosition: SIMD3<Float>?
  private var lastPosition: SIMD3<Float>?
  private var lastYaw: Double?
  private var lastFrameTimestamp: TimeInterval?
  private var lastSelectionTimestamp: TimeInterval = 0
  private var unwrappedYaw = 0.0
  private var direction = 0.0
  private var maxProgress = 0.0
  private var currentTranslationMeters = 0.0
  private var maxTranslationMeters = 0.0
  private var currentPitch = 0.0
  private var currentRoll = 0.0
  private var currentAngularSpeed = 0.0
  private var movingWrongDirection = false
  private var processedFrameCount = 0
  private var rejectedTrackingFrameCount = 0
  private var rejectedTranslationFrameCount = 0
  private var encodedCandidateCount = 0
  private var recordedVideoFrameCount = 0
  private var droppedVideoFrameCount = 0
  private var videoPath = ""
  private var videoTimelinePath = ""
  private var videoWriter: AVAssetWriter?
  private var videoWriterInput: AVAssetWriterInput?
  private var videoWriterAdaptor: AVAssetWriterInputPixelBufferAdaptor?
  private var videoTimeline: [[String: Any]] = []
  private var candidates: [Int: Giro360ArkitFrameCandidate] = [:]
  private var finalCandidates: [Int: Giro360ArkitFrameCandidate] = [:]
  private var selectedLapIndex: Int?
  private var lastStatusEmissionTimestamp: TimeInterval = 0

  override init() {
    super.init()
    session.delegate = self
    session.delegateQueue = stateQueue
  }

  func startCapture(
    directoryPath: String,
    binCount: Int,
    requiredLaps: Int
  ) throws {
    guard ARWorldTrackingConfiguration.isSupported else {
      throw NSError(
        domain: "Giro360Arkit",
        code: 1,
        userInfo: [NSLocalizedDescriptionKey: "ARKit 6-DoF não é suportado neste aparelho."]
      )
    }

    try FileManager.default.createDirectory(
      atPath: directoryPath,
      withIntermediateDirectories: true
    )

    stateQueue.sync {
      self.directoryPath = directoryPath
      self.binCount = binCount
      self.requiredLaps = requiredLaps
      running = true
      finishing = false
      complete = false
      failed = false
      message = "Comece a girar devagar para um lado."
      trackingState = "initializing"
      firstFrameTimestamp = nil
      firstFrameDate = nil
      initialPosition = nil
      lastPosition = nil
      lastYaw = nil
      lastFrameTimestamp = nil
      lastSelectionTimestamp = 0
      unwrappedYaw = 0
      direction = 0
      maxProgress = 0
      currentTranslationMeters = 0
      maxTranslationMeters = 0
      currentPitch = 0
      currentRoll = 0
      currentAngularSpeed = 0
      movingWrongDirection = false
      processedFrameCount = 0
      rejectedTrackingFrameCount = 0
      rejectedTranslationFrameCount = 0
      encodedCandidateCount = 0
      recordedVideoFrameCount = 0
      droppedVideoFrameCount = 0
      videoPath = "\(directoryPath)/giro360_capture.mp4"
      videoTimelinePath = "\(directoryPath)/giro360_video_timeline.json"
      videoWriter = nil
      videoWriterInput = nil
      videoWriterAdaptor = nil
      videoTimeline.removeAll(keepingCapacity: true)
      candidates.removeAll(keepingCapacity: true)
      finalCandidates.removeAll(keepingCapacity: true)
      selectedLapIndex = nil
      lastStatusEmissionTimestamp = 0

      let configuration = ARWorldTrackingConfiguration()
      configuration.worldAlignment = .gravity
      configuration.planeDetection = []
      configuration.environmentTexturing = .none
      if let preferredFormat = ARWorldTrackingConfiguration.supportedVideoFormats.max(
        by: { lhs, rhs in
          let lhsPixels = lhs.imageResolution.width * lhs.imageResolution.height
          let rhsPixels = rhs.imageResolution.width * rhs.imageResolution.height
          if lhsPixels == rhsPixels {
            return lhs.framesPerSecond < rhs.framesPerSecond
          }
          return lhsPixels < rhsPixels
        }
      ) {
        configuration.videoFormat = preferredFormat
      }
      session.run(configuration, options: [.resetTracking, .removeExistingAnchors])
      emitStatus(force: true)
    }
  }

  func cancelCapture() {
    stateQueue.async {
      guard self.running || self.finishing else { return }
      self.running = false
      self.finishing = false
      self.message = "Captura cancelada."
      self.videoWriterInput?.markAsFinished()
      self.videoWriter?.cancelWriting()
      self.session.pause()
      self.emitStatus(force: true)
    }
  }

  func status() -> [String: Any] {
    stateQueue.sync {
      statusSnapshot()
    }
  }

  private func statusSnapshot() -> [String: Any] {
    let totalRadians = Double(requiredLaps) * Double.pi * 2
    let progress = totalRadians > 0 ? min(1, maxProgress / totalRadians) : 0
    let coherentSelection = bestCoherentLapCandidates()
    let statusCandidates = finalCandidates.isEmpty
        ? coherentSelection.frames
        : finalCandidates.values.sorted { $0.binIndex < $1.binIndex }
    let selectedFrames = statusCandidates.map(\.flutterValue)
    let selectedBins = Set(statusCandidates.map(\.binIndex))
    let missingBins = (0..<binCount).filter { !selectedBins.contains($0) }
    let lapCandidateCounts = (0..<requiredLaps).map { lapIndex in
      candidates.values.filter { $0.lapIndex == lapIndex }.count
    }
    let directionLabel: String
    if direction > 0 {
      directionLabel = "right"
    } else if direction < 0 {
      directionLabel = "left"
    } else {
      directionLabel = "pending"
    }

    return [
      "running": running,
      "finishing": finishing,
      "complete": complete,
      "failed": failed,
      "message": message,
      "trackingState": trackingState,
      "direction": directionLabel,
      "movingWrongDirection": movingWrongDirection,
      "progress": progress,
      "progressDegrees": maxProgress * 180 / Double.pi,
      "completedLaps": min(requiredLaps, Int(maxProgress / (Double.pi * 2))),
      "requiredLaps": requiredLaps,
      "binCount": binCount,
      "selectedCount": statusCandidates.count,
      "selectedLap": selectedLapIndex.map { $0 + 1 }
        ?? coherentSelection.lapIndex.map { $0 + 1 }
        ?? 0,
      "lapCandidateCounts": lapCandidateCounts,
      "missingBins": missingBins,
      "currentPitchDegrees": currentPitch * 180 / Double.pi,
      "currentRollDegrees": currentRoll * 180 / Double.pi,
      "currentAngularSpeed": currentAngularSpeed,
      "currentTranslationMeters": currentTranslationMeters,
      "maxTranslationMeters": maxTranslationMeters,
      "processedFrameCount": processedFrameCount,
      "encodedCandidateCount": encodedCandidateCount,
      "recordedVideoFrameCount": recordedVideoFrameCount,
      "droppedVideoFrameCount": droppedVideoFrameCount,
      "videoPath": videoPath,
      "videoTimelinePath": videoTimelinePath,
      "rejectedTrackingFrameCount": rejectedTrackingFrameCount,
      "rejectedTranslationFrameCount": rejectedTranslationFrameCount,
      "frames": selectedFrames,
    ]
  }

  private func emitStatus(
    frameTimestamp: TimeInterval? = nil,
    force: Bool = false
  ) {
    let timestamp = frameTimestamp ?? ProcessInfo.processInfo.systemUptime
    guard force || timestamp - lastStatusEmissionTimestamp >= 0.15 else { return }
    lastStatusEmissionTimestamp = timestamp
    let snapshot = statusSnapshot()
    DispatchQueue.main.async { [weak self] in
      self?.statusDidChange?(snapshot)
    }
  }

  func session(_ session: ARSession, didUpdate frame: ARFrame) {
    guard running, !finishing else { return }
    defer { emitStatus(frameTimestamp: frame.timestamp) }
    processedFrameCount += 1
    trackingState = trackingStateName(frame.camera.trackingState)

    let transform = frame.camera.transform
    let position = SIMD3<Float>(
      transform.columns.3.x,
      transform.columns.3.y,
      transform.columns.3.z
    )
    let forward = SIMD3<Float>(
      -transform.columns.2.x,
      -transform.columns.2.y,
      -transform.columns.2.z
    )
    let yaw = atan2(Double(forward.x), Double(-forward.z))
    let horizontalLength = max(
      0.000_001,
      sqrt(Double(forward.x * forward.x + forward.z * forward.z))
    )
    let pitch = atan2(Double(forward.y), horizontalLength)
    let roll = Double(frame.camera.eulerAngles.z)
    currentPitch = pitch
    currentRoll = roll

    if firstFrameTimestamp == nil {
      firstFrameTimestamp = frame.timestamp
      firstFrameDate = Date()
      initialPosition = position
      lastPosition = position
      lastYaw = yaw
      lastFrameTimestamp = frame.timestamp
      do {
        try startVideoWriter(pixelBuffer: frame.capturedImage)
        appendFrameToVideo(frame)
      } catch {
        failCapture("Não foi possível iniciar o vídeo: \(error.localizedDescription)")
      }
      return
    }

    appendFrameToVideo(frame)
    guard running else { return }

    if let origin = initialPosition {
      currentTranslationMeters = Double(simd_distance(position, origin))
      maxTranslationMeters = max(maxTranslationMeters, currentTranslationMeters)
    }
    lastPosition = position

    guard let previousYaw = lastYaw,
          let previousTimestamp = lastFrameTimestamp else {
      lastYaw = yaw
      lastFrameTimestamp = frame.timestamp
      return
    }
    lastYaw = yaw
    lastFrameTimestamp = frame.timestamp

    let deltaTime = max(0.001, frame.timestamp - previousTimestamp)
    let yawDelta = normalizedAngle(yaw - previousYaw)
    currentAngularSpeed = abs(yawDelta) / deltaTime
    if abs(yawDelta) > 0.45 {
      return
    }
    unwrappedYaw += yawDelta

    videoTimeline.append([
      "videoTimeSeconds": max(0, frame.timestamp - (firstFrameTimestamp ?? frame.timestamp)),
      "relativeYawRadians": unwrappedYaw,
      "pitchRadians": pitch,
      "rollRadians": roll,
      "translationMeters": currentTranslationMeters,
      "angularSpeedRadiansPerSecond": currentAngularSpeed,
      "trackingState": trackingState,
    ])

    if direction == 0, abs(unwrappedYaw) >= 0.18 {
      direction = unwrappedYaw >= 0 ? 1 : -1
      message = "Direção definida. Continue no mesmo sentido por duas voltas."
    }
    guard direction != 0 else { return }

    let directedProgress = direction * unwrappedYaw
    movingWrongDirection = direction * yawDelta < -0.002
    if directedProgress > maxProgress {
      maxProgress = directedProgress
    }

    if currentTranslationMeters > 0.12 {
      rejectedTranslationFrameCount += 1
      message = "A lente saiu do eixo. Reposicione o celular sem voltar a rotação."
    } else if movingWrongDirection {
      message = "Continue no mesmo sentido da volta."
    } else if case .normal = frame.camera.trackingState {
      message = "Continue devagar, mantendo a lente no mesmo ponto."
    } else {
      rejectedTrackingFrameCount += 1
      message = "Rastreamento limitado. Diminua a velocidade e aponte para detalhes."
    }

    let totalProgress = Double(requiredLaps) * Double.pi * 2
    if maxProgress >= totalProgress {
      finishCaptureWhenImagesAreReady()
      return
    }

    guard case .normal = frame.camera.trackingState,
          currentTranslationMeters <= 0.18,
          directedProgress >= maxProgress - 0.08,
          frame.timestamp - lastSelectionTimestamp >= 0.08 else {
      return
    }
    lastSelectionTimestamp = frame.timestamp
    considerFrame(
      frame,
      directedProgress: directedProgress,
      pitch: pitch,
      roll: roll
    )
  }

  func session(_ session: ARSession, didFailWithError error: Error) {
    guard running || finishing else { return }
    running = false
    finishing = false
    failed = true
    message = "Falha no ARKit: \(error.localizedDescription)"
    emitStatus(force: true)
  }

  func sessionWasInterrupted(_ session: ARSession) {
    guard running else { return }
    message = "Captura interrompida. Mantenha o Giro360 aberto."
    emitStatus(force: true)
  }

  func sessionInterruptionEnded(_ session: ARSession) {
    guard running else { return }
    failed = true
    running = false
    message = "A sessão ARKit perdeu a referência. Reinicie as duas voltas."
    emitStatus(force: true)
  }

  private func considerFrame(
    _ frame: ARFrame,
    directedProgress: Double,
    pitch: Double,
    roll: Double
  ) {
    let fullTurn = Double.pi * 2
    let step = fullTurn / Double(binCount)
    let circularYaw = positiveModulo(directedProgress, fullTurn)
    let nearestBin = Int((circularYaw / step).rounded()) % binCount
    let targetYaw = Double(nearestBin) * step
    let centerError = abs(normalizedAngle(circularYaw - targetYaw))
    guard centerError <= step * 0.52 else { return }

    let sharpness = lumaSharpness(frame.capturedImage)
    let trackingBonus = trackingState == "normal" ? 24.0 : 0.0
    let sharpnessScore = min(34.0, sharpness * 0.55)
    let centerScore = max(0, 22.0 * (1 - centerError / (step * 0.52)))
    let speedPenalty = max(0, currentAngularSpeed - 0.28) * 12
    let pitchPenalty = abs(pitch) * 18
    let translationPenalty = currentTranslationMeters * 135
    let score = trackingBonus + sharpnessScore + centerScore
      - speedPenalty - pitchPenalty - translationPenalty

    let lapIndex = min(
      requiredLaps - 1,
      max(0, Int(directedProgress / fullTurn))
    )
    let candidateKey = (lapIndex * binCount) + nearestBin
    let previousBest = candidates[candidateKey]?.qualityScore
      ?? -Double.greatestFiniteMagnitude
    guard score >= previousBest + 0.35 else { return }

    let timestamp = captureDateString(frameTimestamp: frame.timestamp)
    let filePath = String(
      format: "%@/video_%03d.jpg",
      directoryPath,
      nearestBin
    )
    let candidate = Giro360ArkitFrameCandidate(
      binIndex: nearestBin,
      lapIndex: lapIndex,
      filePath: filePath,
      targetYaw: targetYaw,
      relativeYaw: circularYaw,
      pitch: pitch,
      roll: roll,
      translationMeters: currentTranslationMeters,
      qualityScore: score,
      sharpnessScore: sharpness,
      angularSpeed: currentAngularSpeed,
      centerError: centerError,
      trackingState: trackingState,
      capturedAt: timestamp,
      frameTimestamp: frame.timestamp,
      videoTime: max(0, frame.timestamp - (firstFrameTimestamp ?? frame.timestamp)),
      intrinsics: flattened(frame.camera.intrinsics),
      cameraTransform: flattened(frame.camera.transform)
    )
    candidates[candidateKey] = candidate
  }

  private func finishCaptureWhenImagesAreReady() {
    guard !finishing else { return }
    running = false
    finishing = true
    message = "Salvando o vídeo das duas voltas..."
    session.pause()
    emitStatus(force: true)

    guard let writer = videoWriter,
          let writerInput = videoWriterInput else {
      failCapture("O gravador de vídeo não foi inicializado.")
      return
    }

    let coherentSelection = bestCoherentLapCandidates()
    let selectedCandidates = coherentSelection.frames
    selectedLapIndex = coherentSelection.lapIndex
    finalCandidates = Dictionary(
      uniqueKeysWithValues: selectedCandidates.map { ($0.binIndex, $0) }
    )
    let timeline = videoTimeline
    let outputURL = URL(fileURLWithPath: videoPath)
    let timelineURL = URL(fileURLWithPath: videoTimelinePath)
    writerInput.markAsFinished()
    writer.finishWriting { [weak self] in
      guard let self else { return }
      guard writer.status == .completed else {
        let detail = writer.error?.localizedDescription ?? "erro desconhecido"
        self.stateQueue.async {
          self.failCapture("Falha ao salvar o vídeo: \(detail)")
        }
        return
      }

      self.imageQueue.async {
        do {
          let timelineData = try JSONSerialization.data(
            withJSONObject: timeline,
            options: [.prettyPrinted, .sortedKeys]
          )
          try timelineData.write(to: timelineURL, options: .atomic)
          let extractedCount = try self.extractSelectedFrames(
            selectedCandidates,
            from: outputURL
          )
          self.stateQueue.async {
            self.encodedCandidateCount = extractedCount
            self.finishing = false
            let minimumFrameCount = max(24, Int(Double(self.binCount) * 0.75))
            if extractedCount < minimumFrameCount {
              self.failed = true
              self.message = "O vídeo foi salvo, mas só \(extractedCount)/\(self.binCount) frames puderam ser extraídos."
            } else {
              self.complete = true
              let selectedLap = (self.selectedLapIndex ?? 0) + 1
              self.message = "Vídeo salvo. Volta \(selectedLap) selecionada com \(extractedCount) frames."
            }
            self.emitStatus(force: true)
          }
        } catch {
          self.stateQueue.async {
            self.failCapture("O vídeo foi salvo, mas a extração falhou: \(error.localizedDescription)")
          }
        }
      }
    }
  }

  private func startVideoWriter(pixelBuffer: CVPixelBuffer) throws {
    let outputURL = URL(fileURLWithPath: videoPath)
    if FileManager.default.fileExists(atPath: outputURL.path) {
      try FileManager.default.removeItem(at: outputURL)
    }
    let width = CVPixelBufferGetWidth(pixelBuffer)
    let height = CVPixelBufferGetHeight(pixelBuffer)
    let writer = try AVAssetWriter(outputURL: outputURL, fileType: .mp4)
    let settings: [String: Any] = [
      AVVideoCodecKey: AVVideoCodecType.h264,
      AVVideoWidthKey: width,
      AVVideoHeightKey: height,
      AVVideoCompressionPropertiesKey: [
        AVVideoAverageBitRateKey: 24_000_000,
        AVVideoExpectedSourceFrameRateKey: 30,
        AVVideoMaxKeyFrameIntervalKey: 30,
      ],
    ]
    let input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
    input.expectsMediaDataInRealTime = true
    input.transform = CGAffineTransform(
      a: 0,
      b: 1,
      c: -1,
      d: 0,
      tx: CGFloat(height),
      ty: 0
    )
    guard writer.canAdd(input) else {
      throw NSError(
        domain: "Giro360Arkit",
        code: 3,
        userInfo: [NSLocalizedDescriptionKey: "O iPhone recusou o formato H.264 solicitado."]
      )
    }
    writer.add(input)
    let adaptor = AVAssetWriterInputPixelBufferAdaptor(
      assetWriterInput: input,
      sourcePixelBufferAttributes: nil
    )
    guard writer.startWriting() else {
      throw writer.error ?? NSError(
        domain: "Giro360Arkit",
        code: 4,
        userInfo: [NSLocalizedDescriptionKey: "O encoder H.264 não iniciou."]
      )
    }
    writer.startSession(atSourceTime: .zero)
    videoWriter = writer
    videoWriterInput = input
    videoWriterAdaptor = adaptor
  }

  private func appendFrameToVideo(_ frame: ARFrame) {
    guard let startedAt = firstFrameTimestamp,
          let writer = videoWriter,
          let input = videoWriterInput,
          let adaptor = videoWriterAdaptor,
          writer.status == .writing else {
      return
    }
    guard input.isReadyForMoreMediaData else {
      droppedVideoFrameCount += 1
      return
    }
    let presentationTime = CMTime(
      seconds: max(0, frame.timestamp - startedAt),
      preferredTimescale: 600
    )
    if adaptor.append(frame.capturedImage, withPresentationTime: presentationTime) {
      recordedVideoFrameCount += 1
    } else {
      droppedVideoFrameCount += 1
      if writer.status == .failed {
        failCapture(
          "O encoder interrompeu a gravação: \(writer.error?.localizedDescription ?? "erro desconhecido")"
        )
      }
    }
  }

  private func extractSelectedFrames(
    _ selectedCandidates: [Giro360ArkitFrameCandidate],
    from videoURL: URL
  ) throws -> Int {
    let asset = AVURLAsset(url: videoURL)
    let generator = AVAssetImageGenerator(asset: asset)
    generator.appliesPreferredTrackTransform = true
    generator.requestedTimeToleranceBefore = CMTime(value: 1, timescale: 30)
    generator.requestedTimeToleranceAfter = CMTime(value: 1, timescale: 30)

    var extractedCount = 0
    for candidate in selectedCandidates {
      let time = CMTime(
        seconds: candidate.videoTime,
        preferredTimescale: 600
      )
      var actualTime = CMTime.zero
      let cgImage = try generator.copyCGImage(at: time, actualTime: &actualTime)
      guard let data = UIImage(cgImage: cgImage).jpegData(compressionQuality: 0.94) else {
        continue
      }
      try data.write(
        to: URL(fileURLWithPath: candidate.filePath),
        options: .atomic
      )
      extractedCount += 1
    }
    return extractedCount
  }

  private func bestCoherentLapCandidates()
      -> (lapIndex: Int?, frames: [Giro360ArkitFrameCandidate]) {
    var bestLapIndex: Int?
    var bestFrames: [Giro360ArkitFrameCandidate] = []
    var bestScore = -Double.greatestFiniteMagnitude

    for lapIndex in 0..<requiredLaps {
      let frames = candidates.values
        .filter { $0.lapIndex == lapIndex }
        .sorted { $0.binIndex < $1.binIndex }
      guard !frames.isEmpty else { continue }
      let score = coherentLapScore(frames)
      if frames.count > bestFrames.count ||
          (frames.count == bestFrames.count && score > bestScore) {
        bestLapIndex = lapIndex
        bestFrames = frames
        bestScore = score
      }
    }
    return (bestLapIndex, bestFrames)
  }

  private func coherentLapScore(
    _ frames: [Giro360ArkitFrameCandidate]
  ) -> Double {
    guard !frames.isEmpty else { return -Double.greatestFiniteMagnitude }
    let count = Double(frames.count)
    let averageSharpness = frames.map(\.sharpnessScore).reduce(0, +) / count
    let averageCenterError = frames.map(\.centerError).reduce(0, +) / count
    let averageAngularSpeed = frames.map(\.angularSpeed).reduce(0, +) / count
    let pitchValues = frames.map(\.pitch)
    let pitchSpan = (pitchValues.max() ?? 0) - (pitchValues.min() ?? 0)
    let translationValues = frames.map(\.translationMeters)
    let translationSpan =
      (translationValues.max() ?? 0) - (translationValues.min() ?? 0)
    let cameraPositionSpan = coherentCameraPositionSpan(frames)

    return (averageSharpness * 0.8)
      - (pitchSpan * 80)
      - (translationSpan * 120)
      - (cameraPositionSpan * 180)
      - (averageCenterError * 50)
      - (averageAngularSpeed * 3)
  }

  private func coherentCameraPositionSpan(
    _ frames: [Giro360ArkitFrameCandidate]
  ) -> Double {
    let positions = frames.compactMap { frame -> SIMD3<Double>? in
      guard frame.cameraTransform.count == 16 else { return nil }
      return SIMD3<Double>(
        frame.cameraTransform[3],
        frame.cameraTransform[7],
        frame.cameraTransform[11]
      )
    }
    guard let first = positions.first else { return 0 }
    var minimum = first
    var maximum = first
    for position in positions.dropFirst() {
      minimum = simd_min(minimum, position)
      maximum = simd_max(maximum, position)
    }
    return simd_length(maximum - minimum)
  }

  private func failCapture(_ detail: String) {
    running = false
    finishing = false
    failed = true
    message = detail
    session.pause()
    emitStatus(force: true)
  }

  private func lumaSharpness(_ pixelBuffer: CVPixelBuffer) -> Double {
    CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
    defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }

    let planeCount = CVPixelBufferGetPlaneCount(pixelBuffer)
    let width: Int
    let height: Int
    let bytesPerRow: Int
    let baseAddress: UnsafeMutableRawPointer?
    let channelStride: Int
    if planeCount > 0 {
      width = CVPixelBufferGetWidthOfPlane(pixelBuffer, 0)
      height = CVPixelBufferGetHeightOfPlane(pixelBuffer, 0)
      bytesPerRow = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)
      baseAddress = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0)
      channelStride = 1
    } else {
      width = CVPixelBufferGetWidth(pixelBuffer)
      height = CVPixelBufferGetHeight(pixelBuffer)
      bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)
      baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer)
      channelStride = 4
    }
    guard let baseAddress, width > 24, height > 24 else { return 0 }

    let bytes = baseAddress.assumingMemoryBound(to: UInt8.self)
    let sampleStep = 12
    var total = 0.0
    var samples = 0
    for y in stride(from: sampleStep, to: height - sampleStep, by: sampleStep) {
      let row = bytes + y * bytesPerRow
      let nextRow = bytes + (y + sampleStep) * bytesPerRow
      for x in stride(from: sampleStep, to: width - sampleStep, by: sampleStep) {
        let offset = x * channelStride
        let value = Int(row[offset])
        total += Double(abs(value - Int(row[offset + sampleStep * channelStride])))
        total += Double(abs(value - Int(nextRow[offset])))
        samples += 2
      }
    }
    return samples == 0 ? 0 : total / Double(samples)
  }

  private func captureDateString(frameTimestamp: TimeInterval) -> String {
    let baseTimestamp = firstFrameTimestamp ?? frameTimestamp
    let baseDate = firstFrameDate ?? Date()
    let date = baseDate.addingTimeInterval(frameTimestamp - baseTimestamp)
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return formatter.string(from: date)
  }

  private func trackingStateName(_ state: ARCamera.TrackingState) -> String {
    switch state {
    case .normal:
      return "normal"
    case .notAvailable:
      return "not_available"
    case .limited(let reason):
      switch reason {
      case .initializing:
        return "limited_initializing"
      case .excessiveMotion:
        return "limited_excessive_motion"
      case .insufficientFeatures:
        return "limited_insufficient_features"
      case .relocalizing:
        return "limited_relocalizing"
      @unknown default:
        return "limited_unknown"
      }
    }
  }

  private func normalizedAngle(_ value: Double) -> Double {
    var result = value
    while result <= -Double.pi { result += Double.pi * 2 }
    while result > Double.pi { result -= Double.pi * 2 }
    return result
  }

  private func positiveModulo(_ value: Double, _ modulus: Double) -> Double {
    let result = value.truncatingRemainder(dividingBy: modulus)
    return result < 0 ? result + modulus : result
  }

  private func flattened(_ matrix: simd_float3x3) -> [Double] {
    (0..<3).flatMap { row in
      (0..<3).map { column in Double(matrix[column][row]) }
    }
  }

  private func flattened(_ matrix: simd_float4x4) -> [Double] {
    (0..<4).flatMap { row in
      (0..<4).map { column in Double(matrix[column][row]) }
    }
  }
}
