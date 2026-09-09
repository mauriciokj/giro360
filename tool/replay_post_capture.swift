import Foundation

@main
struct ReplayPostCapture {
  static func main() throws {
    let args = CommandLine.arguments
    guard args.count == 4 else {
      fputs("Usage: replay_post_capture <capture-directory> <fresh-output-directory> <bins>\n", stderr)
      exit(2)
    }
    let source = URL(fileURLWithPath: args[1])
    let timelineURL = source.appendingPathComponent("giro360_video_timeline.json")
    let timeline = try JSONSerialization.jsonObject(with: Data(contentsOf: timelineURL)) as! [[String: Any]]
    let result = try Giro360VideoProcessor.process(video: source.appendingPathComponent("giro360_capture.mp4"),
      timeline: timeline, output: URL(fileURLWithPath: args[2]), bins: Int(args[3])!, laps: 2) { print($0) }
    let summary = result.filter { $0.key != "frames" }
    print(String(data: try JSONSerialization.data(withJSONObject: summary, options: [.prettyPrinted, .sortedKeys]), encoding: .utf8)!)
  }
}
