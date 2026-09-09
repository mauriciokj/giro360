import Foundation

@main
struct SelectionTests {
  static func main() throws {
    let turn = Double.pi * 2
    let bins = 60
    var rows: [Giro360ProfiledFrame] = []
    for lap in 0..<2 {
      for bin in 1...bins {
        let yaw = Double(lap) * turn + Double(bin) * turn / Double(bins) - 0.001
        let pitch = lap == 0 ? (bin < 20 ? -0.26 : -0.02) : -0.035
        var matrix = [1.0,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1]
        // A constant offset from the origin must not eliminate a coherent lap.
        matrix[3] = lap == 1 ? 0.21 : Double(bin) / 300
        let sample: [String: Any] = ["cameraTransform":matrix, "pitchRadians":pitch,
          "angularSpeedRadiansPerSecond":0.2, "translationMeters":abs(matrix[3])]
        rows.append(Giro360ProfiledFrame(sample:sample, frameIndex:rows.count, pts:Double(rows.count),
          progress:yaw, sharpness:lap == 0 ? 1000 : 500, overlapSharpness:lap == 0 ? 1000 : 500))
      }
    }
    let selections = Giro360VideoSelector.select(rows, bins:bins, laps:2)
    precondition(selections.count == 2 && selections[0].lapIndex == 1)
    precondition(selections[0].frames.count == bins)
    precondition((selections[0].frames[0]["frameIndex"] as? Int) == 119)
    let missing = rows.filter { $0.frameIndex != 90 }
    precondition(Giro360VideoSelector.select(missing, bins:bins, laps:2)[0].lapIndex == 0)
    let closed = selections.first { $0.lapIndex == 0 }!
    precondition(closed.maxAdjacentDistance > 0.19, "Circular neighbour must be scored")
    let reversedTime = rows.map { r in Giro360ProfiledFrame(sample:r.sample, frameIndex:r.frameIndex,
      pts:-r.pts, progress:r.progress, sharpness:r.sharpness, overlapSharpness:r.overlapSharpness) }
    precondition(Giro360VideoSelector.select(reversedTime, bins:bins, laps:2).isEmpty)
    precondition(Giro360VideoSelector.select([], bins:bins, laps:2).isEmpty)
    print("PASS: coherent displaced lap; full coverage; closing sector; circular distance; temporal order; empty capture")
    if CommandLine.arguments.count > 1 {
      let data = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
      let root = try JSONSerialization.jsonObject(with:data) as! [String:Any]
      let frames = root["frames"] as! [[String:Any]]
      let real = frames.map { f in Giro360ProfiledFrame(sample:f, frameIndex:f["frameIndex"] as! Int,
        pts:f["ptsSeconds"] as! Double, progress:f["relativeYawRadians"] as! Double,
        sharpness:f["sharpness"] as! Double, overlapSharpness:f["overlapSharpness"] as! Double) }
      let selected = Giro360VideoSelector.select(real, bins:60, laps:2)
      precondition(selected.first?.lapIndex == 1 && selected.first?.frames.count == 60)
      print("PASS: recorded handheld profile selects 60 frames from lap 2")
    }
  }
}
