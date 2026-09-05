// macOS integration check: encode variable timestamps with intentional gaps,
// then extract each retained frame using the capture pipeline's exact clock.
import Foundation
import AVFoundation
import CoreVideo
import AppKit

let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: directory) }
let url = directory.appendingPathComponent("timing.mp4")
let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
  AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: 128, AVVideoHeightKey: 128
])
let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input,
  sourcePixelBufferAttributes: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
    kCVPixelBufferWidthKey as String: 128, kCVPixelBufferHeightKey as String: 128])
writer.add(input)
precondition(writer.startWriting())
writer.startSession(atSourceTime: .zero)
var samples: [(CMTime, Int)] = []
for index in 0..<60 where index % 7 != 3 {
  let deadline = Date().addingTimeInterval(5)
  while !input.isReadyForMoreMediaData && Date() < deadline { Thread.sleep(forTimeInterval: 0.005) }
  precondition(input.isReadyForMoreMediaData)
  var buffer: CVPixelBuffer?
  CVPixelBufferPoolCreatePixelBuffer(nil, adaptor.pixelBufferPool!, &buffer)
  let pixelBuffer = buffer!
  CVPixelBufferLockBaseAddress(pixelBuffer, [])
  let base = CVPixelBufferGetBaseAddress(pixelBuffer)!.assumingMemoryBound(to: UInt8.self)
  let stride = CVPixelBufferGetBytesPerRow(pixelBuffer)
  for y in 0..<128 { for x in 0..<128 {
    let brightness: UInt8 = ((index >> (x / 16)) & 1) == 1 ? 225 : 25
    let offset = y * stride + x * 4
    base[offset] = brightness; base[offset + 1] = brightness
    base[offset + 2] = brightness; base[offset + 3] = 255
  }}
  CVPixelBufferUnlockBaseAddress(pixelBuffer, [])
  let time = CMTime(value: Int64(index * 10 + (index / 10) * 3), timescale: 600)
  precondition(adaptor.append(pixelBuffer, withPresentationTime: time))
  samples.append((time, index))
}
input.markAsFinished()
let finished = DispatchSemaphore(value: 0)
writer.finishWriting { finished.signal() }
precondition(finished.wait(timeout: .now() + 15) == .success)
precondition(writer.status == .completed)
let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
generator.requestedTimeToleranceBefore = .zero
generator.requestedTimeToleranceAfter = .zero
for (time, expectedIndex) in samples {
  var actual = CMTime.zero
  let image = try generator.copyCGImage(at: time, actualTime: &actual)
  precondition(abs(CMTimeGetSeconds(CMTimeSubtract(actual, time))) < 1.0 / 1200)
  let bitmap = NSBitmapImageRep(cgImage: image)
  var decodedIndex = 0
  for bit in 0..<8 {
    let color = bitmap.colorAt(x: bit * 16 + 8, y: 64)!.usingColorSpace(.deviceRGB)!
    if color.redComponent > 0.5 { decodedIndex |= (1 << bit) }
  }
  precondition(decodedIndex == expectedIndex, "Extracted a neighbouring frame")
}
print("Verified \(samples.count) image/pose timestamps and pixel identities, including recording gaps.")
