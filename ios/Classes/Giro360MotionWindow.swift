import Foundation

/// Estimated camera displacement over a short window of normal ARKit tracking.
/// A constant offset from the start of the session is not itself recent motion.
struct Giro360MotionWindow {
  private struct Sample {
    let timestamp: TimeInterval
    let position: SIMD3<Double>
  }

  private let duration: TimeInterval = 2.0
  private let sampleInterval: TimeInterval = 0.1
  private let maxGap: TimeInterval = 0.5
  private var samples: [Sample] = []
  private var lastInputTime: TimeInterval?
  private var lastResult: Double?

  mutating func reset() {
    samples.removeAll(keepingCapacity: true)
    lastInputTime = nil
    lastResult = nil
  }

  mutating func record(timestamp: TimeInterval, position: SIMD3<Double>) -> Double? {
    guard timestamp.isFinite, position.x.isFinite, position.y.isFinite,
          position.z.isFinite else {
      reset()
      return nil
    }
    if let previous = lastInputTime,
       timestamp <= previous || timestamp - previous > maxGap {
      reset()
    }
    lastInputTime = timestamp
    if let last = samples.last, timestamp - last.timestamp < sampleInterval {
      return lastResult
    }
    samples.append(Sample(timestamp: timestamp, position: position))
    samples.removeAll { timestamp - $0.timestamp > duration }
    guard samples.count >= 2 else {
      lastResult = nil
      return nil
    }
    var maximum = 0.0
    for i in samples.indices {
      for j in samples.indices where j > i {
        let difference = samples[i].position - samples[j].position
        let distance = sqrt(difference.x * difference.x + difference.y * difference.y
                            + difference.z * difference.z)
        maximum = max(maximum, distance)
      }
    }
    lastResult = maximum
    return maximum
  }
}
