import Foundation

struct Giro360ProfiledFrame {
  let sample: [String: Any]
  let frameIndex: Int
  let pts: Double
  let progress: Double
  let sharpness: Double
  let overlapSharpness: Double
  var pitch: Double { sample["pitchRadians"] as? Double ?? 0 }
  var speed: Double { abs(sample["angularSpeedRadiansPerSecond"] as? Double ?? 0) }
  var position: [Double] {
    let matrix = sample["cameraTransform"] as! [Double]
    return [matrix[3], matrix[7], matrix[11]]
  }
}

struct Giro360VideoSelection {
  let lapIndex: Int
  let frames: [[String: Any]]
  let score: Double
  let pitchSpan: Double
  let maxAdjacentDistance: Double
}

/// Scores complete, temporally ordered laps, including the circular neighbour.
/// Distance to the session origin is diagnostic, not a sector eligibility gate.
struct Giro360VideoSelector {
  static func percentile(_ values: [Double], _ fraction: Double) -> Double {
    guard !values.isEmpty else { return 0 }
    let sorted = values.sorted()
    let index = Double(sorted.count - 1) * fraction
    let lower = Int(index)
    return sorted[lower] + (sorted[min(lower + 1, sorted.count - 1)] - sorted[lower]) * (index - Double(lower))
  }

  static func select(_ rows: [Giro360ProfiledFrame], bins: Int, laps: Int) -> [Giro360VideoSelection] {
    let turn = Double.pi * 2
    let step = turn / Double(bins)
    return (0..<laps).compactMap { lap in
      let start = Double(lap) * turn
      let candidates = rows.filter { $0.progress >= start && $0.progress <= start + turn }
      guard !candidates.isEmpty else { return nil }
      let medianPitch = percentile(candidates.map(\.pitch), 0.5)
      var chosen: [Giro360ProfiledFrame] = []
      var frames: [[String: Any]] = []
      for bin in 0..<bins {
        // Sector zero comes from the end of this lap, never the next lap.
        let target = start + (bin == 0 ? turn : Double(bin) * step)
        let nearby = candidates.filter { abs($0.progress - target) < step * 0.42 }
        guard !nearby.isEmpty else { continue }
        let sharpness = nearby.map { log1p($0.overlapSharpness) }
        let low = percentile(sharpness, 0.1), high = percentile(sharpness, 0.9)
        func cost(_ frame: Giro360ProfiledFrame) -> Double {
          let normalizedSharpness = min(1, max(0, (log1p(frame.overlapSharpness) - low) / max(high - low, 1e-6)))
          return abs(frame.progress - target) / step + 0.2 * frame.speed
            + 0.5 * abs(frame.pitch - medianPitch) - 0.15 * normalizedSharpness
        }
        let best = nearby.min { cost($0) < cost($1) }!
        var value = best.sample
        value["binIndex"] = bin
        value["lapIndex"] = lap
        value["relativeYawRadians"] = best.progress.truncatingRemainder(dividingBy: turn)
        value["targetYawRadians"] = Double(bin) * step
        value["centerErrorRadians"] = abs(best.progress - target)
        value["sharpnessScore"] = best.sharpness
        value["overlapSharpnessScore"] = best.overlapSharpness
        value["qualityScore"] = -cost(best)
        value["frameIndex"] = best.frameIndex
        value["ptsSeconds"] = best.pts
        value["selectionSource"] = "post_capture_continuity_v1"
        chosen.append(best)
        frames.append(value)
      }
      guard frames.count >= max(24, Int(Double(bins) * 0.75)) else { return nil }
      let chronological = chosen.sorted { $0.progress < $1.progress }
      guard zip(chronological, chronological.dropFirst()).allSatisfy({ $0.pts < $1.pts }) else { return nil }
      let pitches = chosen.map(\.pitch)
      let pitchSpan = (pitches.max() ?? 0) - (pitches.min() ?? 0)
      let distances = chosen.indices.map { i -> Double in
        let a = chosen[i].position, b = chosen[(i + 1) % chosen.count].position
        return sqrt(zip(a, b).reduce(0) { $0 + pow($1.0 - $1.1, 2) })
      }
      let positionSpan = sqrt((0..<3).reduce(0.0) { total, axis in
        let values = chosen.map { $0.position[axis] }
        return total + pow(values.max()! - values.min()!, 2)
      })
      let count = Double(chosen.count)
      let score = pitchSpan * 4 + distances.reduce(0, +) / count * 4
        + (distances.max() ?? 0) * 2 + positionSpan
        + chosen.map(\.speed).reduce(0, +) / count * 0.1
        - percentile(chosen.map { log1p($0.overlapSharpness) }, 0.5) * 0.05
      return Giro360VideoSelection(lapIndex: lap, frames: frames, score: score,
                                  pitchSpan: pitchSpan, maxAdjacentDistance: distances.max() ?? 0)
    }.sorted {
      if $0.frames.count != $1.frames.count { return $0.frames.count > $1.frames.count }
      if $0.score != $1.score { return $0.score < $1.score }
      return $0.lapIndex < $1.lapIndex
    }
  }
}
