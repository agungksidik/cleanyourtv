// Crops a folder of photos to 1280x720 wallpapers that keep every face in the frame (macOS only: Vision).
// Faces are found with Apple's Vision framework on the computer; nothing is uploaded. A photo whose faces do not
// fit one 16:9 window (often a tall portrait of several people) is skipped and named in the output.
//
// Usage: swift tools/facecrop.swift ~/Pictures/tv work/<theme>/wallpapers
// The output folder is in work/, which git ignores: family photos never go into a commit.
import Foundation
import Vision
import CoreImage
import ImageIO
import UniformTypeIdentifiers

let args = CommandLine.arguments
let inDir = URL(fileURLWithPath: args[1]), outDir = URL(fileURLWithPath: args[2])
let W: CGFloat = 1280, H: CGFloat = 720
let ctx = CIContext()

func write(_ image: CIImage, _ url: URL) {
    let cs = CGColorSpace(name: CGColorSpace.sRGB)!
    guard let cg = ctx.createCGImage(image, from: CGRect(x: 0, y: 0, width: W, height: H), format: .RGBA8, colorSpace: cs),
          let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil) else { return }
    CGImageDestinationAddImage(dest, cg, [kCGImageDestinationLossyCompressionQuality: 0.85] as CFDictionary)
    CGImageDestinationFinalize(dest)
}

let files = try FileManager.default.contentsOfDirectory(at: inDir, includingPropertiesForKeys: nil)
    .filter { ["jpg", "jpeg", "png", "heic"].contains($0.pathExtension.lowercased()) }
    .sorted { $0.lastPathComponent < $1.lastPathComponent }
var n = 0
for file in files {
    // EXIF orientation applied, so Vision and the crop see the photo upright.
    guard let raw = CIImage(contentsOf: file, options: [.applyOrientationProperty: true]) else { continue }
    let img = raw.transformed(by: CGAffineTransform(translationX: -raw.extent.minX, y: -raw.extent.minY))
    let iw = img.extent.width, ih = img.extent.height
    let req = VNDetectFaceRectanglesRequest()
    try? VNImageRequestHandler(ciImage: img, options: [:]).perform([req])
    // Vision boxes are normalised, origin bottom-left, like CIImage.
    var faces = (req.results ?? []).map { r -> CGRect in
        let b = r.boundingBox
        return CGRect(x: b.minX * iw, y: b.minY * ih, width: b.width * iw, height: b.height * ih)
    }.filter { $0.width > iw * 0.02 }
    // Room for hair above and chin below each face.
    faces = faces.map { $0.insetBy(dx: -$0.width * 0.25, dy: -$0.height * 0.35) }
    let union = faces.reduce(CGRect.null) { $0.union($1) }

    // The largest 16:9 window inside the photo.
    var cw = iw, ch = iw * H / W
    if ch > ih { ch = ih; cw = ih * W / H }
    var mode = "center"
    let out: CIImage
    if union.isNull || (union.height <= ch && union.width <= cw) {
        var x = (iw - cw) / 2, y = (ih - ch) / 2
        if !union.isNull {
            // Faces a little above the middle of the window, clamped to the photo.
            let fy = union.midY - ch * 0.55
            y = min(max(fy, 0), ih - ch)
            y = min(max(y, union.maxY - ch), union.minY)
            y = min(max(y, 0), ih - ch)
            x = min(max(union.midX - cw / 2, 0), iw - cw)
            mode = "faces:\(faces.count)"
        }
        out = img.cropped(to: CGRect(x: x, y: y, width: cw, height: ch))
            .transformed(by: CGAffineTransform(translationX: -x, y: -y))
            .transformed(by: CGAffineTransform(scaleX: W / cw, y: W / cw))
    } else {
        print("skipped \(file.lastPathComponent): \(faces.count) faces do not fit a 16:9 frame")
        continue
    }
    n += 1
    let name = String(format: "wp%02d.jpg", n)
    write(out, outDir.appendingPathComponent(name))
    print("\(name) \(file.lastPathComponent) \(Int(iw))x\(Int(ih)) \(mode)")
}
