import Foundation

@main
struct ProcessorTests {
  static func main() throws {
    let source = URL(fileURLWithPath: CommandLine.arguments[1])
    let video = source.appendingPathComponent("giro360_capture.mp4")
    let timeline = try JSONSerialization.jsonObject(with: Data(contentsOf: source.appendingPathComponent("giro360_video_timeline.json"))) as! [[String: Any]]
    let output = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    func rejected(_ name: String, _ work: () throws -> Void) {
      do { try work(); fatalError("Expected rejection: \(name)") }
      catch { print("PASS: \(name)") }
    }
    rejected("existing source directory cannot be an output") {
      _ = try Giro360VideoProcessor.process(video:video, timeline:timeline, output:source, bins:60, laps:2)
    }
    var invalid = timeline
    invalid[1]["videoTimeSeconds"] = invalid[0]["videoTimeSeconds"]
    rejected("duplicate telemetry timestamps") {
      _ = try Giro360VideoProcessor.process(video:video, timeline:invalid, output:output, bins:60, laps:2)
    }
    let token = Giro360ProcessingCancellation(); token.cancel()
    rejected("cancelled decoding") {
      _ = try Giro360VideoProcessor.process(video:video, timeline:timeline, output:output, bins:60, laps:2, cancellation:token)
    }
    precondition(!FileManager.default.fileExists(atPath:output.path))
    print("PASS: cancellation and invalid inputs leave no partial output")
  }
}
