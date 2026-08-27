import CoreGraphics
import CoreImage
import CoreML
import Foundation
import ImageIO
import Vision

struct Options {
  let model: URL
  let output: URL
  let images: [URL]
}

func parseOptions() throws -> Options {
  let arguments = Array(CommandLine.arguments.dropFirst())
  guard arguments.count >= 4 else {
    throw NSError(
      domain: "Giro360Depth",
      code: 64,
      userInfo: [
        NSLocalizedDescriptionKey:
          "Usage: swift depth_anything.swift --model <model.mlpackage> --output <directory> <images...>"
      ]
    )
  }
  guard let modelIndex = arguments.firstIndex(of: "--model"), modelIndex + 1 < arguments.count,
        let outputIndex = arguments.firstIndex(of: "--output"), outputIndex + 1 < arguments.count else {
    throw NSError(domain: "Giro360Depth", code: 64)
  }
  let model = URL(fileURLWithPath: arguments[modelIndex + 1])
  let output = URL(fileURLWithPath: arguments[outputIndex + 1], isDirectory: true)
  let reserved = Set([modelIndex, modelIndex + 1, outputIndex, outputIndex + 1])
  let images = arguments.enumerated().compactMap { index, value in
    reserved.contains(index) ? nil : URL(fileURLWithPath: value)
  }
  return Options(model: model, output: output, images: images)
}

func loadImage(_ url: URL) throws -> CGImage {
  guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
        let image = CGImageSourceCreateImageAtIndex(source, 0, nil) else {
    throw NSError(
      domain: "Giro360Depth",
      code: 65,
      userInfo: [NSLocalizedDescriptionKey: "Could not load \(url.path)"]
    )
  }
  return image
}

func depthValues(from buffer: CVPixelBuffer) throws -> (values: [Float], width: Int, height: Int) {
  CVPixelBufferLockBaseAddress(buffer, .readOnly)
  defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
  guard let baseAddress = CVPixelBufferGetBaseAddress(buffer) else {
    throw NSError(domain: "Giro360Depth", code: 66)
  }
  let width = CVPixelBufferGetWidth(buffer)
  let height = CVPixelBufferGetHeight(buffer)
  let rowBytes = CVPixelBufferGetBytesPerRow(buffer)
  let format = CVPixelBufferGetPixelFormatType(buffer)
  var values = [Float](repeating: 0, count: width * height)

  for row in 0..<height {
    let rowAddress = baseAddress.advanced(by: row * rowBytes)
    if format == kCVPixelFormatType_OneComponent16Half {
      let pixels = rowAddress.assumingMemoryBound(to: UInt16.self)
      for column in 0..<width {
        values[row * width + column] = Float(Float16(bitPattern: pixels[column]))
      }
    } else if format == kCVPixelFormatType_OneComponent32Float {
      let pixels = rowAddress.assumingMemoryBound(to: Float.self)
      for column in 0..<width {
        values[row * width + column] = pixels[column]
      }
    } else {
      throw NSError(
        domain: "Giro360Depth",
        code: 67,
        userInfo: [NSLocalizedDescriptionKey: "Unsupported depth pixel format: \(format)"]
      )
    }
  }
  return (values, width, height)
}

func writeRaw(_ values: [Float], to url: URL) throws {
  let data = values.withUnsafeBufferPointer { Data(buffer: $0) }
  try data.write(to: url)
}

func writePreview(_ values: [Float], width: Int, height: Int, to url: URL) throws {
  let finite = values.filter(\.isFinite).sorted()
  guard !finite.isEmpty else { throw NSError(domain: "Giro360Depth", code: 68) }
  let lower = finite[Int(Double(finite.count - 1) * 0.02)]
  let upper = finite[Int(Double(finite.count - 1) * 0.98)]
  let scale = max(upper - lower, Float.ulpOfOne)
  let pixels = values.map { value -> UInt16 in
    let normalized = min(1, max(0, (value - lower) / scale))
    return UInt16(normalized * Float(UInt16.max)).bigEndian
  }
  var data = Data("P5\n\(width) \(height)\n65535\n".utf8)
  pixels.withUnsafeBytes { data.append(contentsOf: $0) }
  try data.write(to: url)
}

do {
  let options = try parseOptions()
  try FileManager.default.createDirectory(
    at: options.output,
    withIntermediateDirectories: true
  )
  let compiled = try MLModel.compileModel(at: options.model)
  let configuration = MLModelConfiguration()
  configuration.computeUnits = .all
  let model = try MLModel(contentsOf: compiled, configuration: configuration)

  for imageURL in options.images {
    let image = try loadImage(imageURL)
    let input = try MLFeatureValue(
      cgImage: image,
      pixelsWide: 518,
      pixelsHigh: 392,
      pixelFormatType: kCVPixelFormatType_32BGRA,
      options: [.cropAndScale: VNImageCropAndScaleOption.centerCrop.rawValue]
    )
    let provider = try MLDictionaryFeatureProvider(dictionary: ["image": input])
    let prediction = try model.prediction(from: provider)
    guard let buffer = prediction.featureValue(for: "depth")?.imageBufferValue else {
      throw NSError(domain: "Giro360Depth", code: 69)
    }
    let depth = try depthValues(from: buffer)
    let stem = imageURL.deletingPathExtension().lastPathComponent
    try writeRaw(depth.values, to: options.output.appendingPathComponent("\(stem).depth.f32"))
    try writePreview(
      depth.values,
      width: depth.width,
      height: depth.height,
      to: options.output.appendingPathComponent("\(stem).depth.pgm")
    )
    print("image=\(imageURL.lastPathComponent) width=\(depth.width) height=\(depth.height)")
  }
} catch {
  fputs("\(error.localizedDescription)\n", stderr)
  exit(1)
}
