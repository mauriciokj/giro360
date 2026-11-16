import Foundation

@main
struct MotionWindowTests {
  static func main() {
    var window = Giro360MotionWindow()
    func sample(_ time: Double, _ x: Double) -> Double? {
      window.record(timestamp: time, position: SIMD3<Double>(x, 0, 0))
    }

    precondition(sample(0, 0.20) == nil)
    precondition(sample(0.2, 0.20) == 0, "Offset from origin is not recent motion")
    precondition(abs(sample(0.4, 0.26)! - 0.06) < 1e-9)
    precondition(abs(sample(0.6, 0.20)! - 0.06) < 1e-9, "An excursion remains visible after returning")
    for i in 4...17 { _ = sample(Double(i) * 0.2, 0.20) }
    precondition(sample(3.6, 0.20) == 0, "Old movement expires")
    window.reset()
    precondition(sample(4, 0.4) == nil, "Tracking loss clears the window")
    precondition(sample(3, 0.4) == nil, "Clock reversal starts a new window")
    precondition(sample(3.2, .nan) == nil, "Invalid poses are not reported as stable")
    precondition(sample(3.4, 0.4) == nil)
    precondition(sample(4.1, 0.4) == nil, "A long tracking gap starts a new window")
    print("PASS: offset, recent motion, excursions, expiration, tracking gaps and invalid poses")
  }
}
