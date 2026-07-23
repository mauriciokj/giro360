Pod::Spec.new do |s|
  s.name             = 'giro360_capture'
  s.version          = '0.0.1'
  s.summary          = 'ARKit video capture and OpenCV panorama engine.'
  s.description      = <<-DESC
Records ARKit camera video, selects coherent keyframes, and stitches a
cylindrical panorama locally with OpenCV.
                       DESC
  s.homepage         = 'https://github.com/mauriciokj/giro360'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Giro360' => 'mauriciokj@users.noreply.github.com' }
  s.source           = { :path => '.' }
  s.source_files     = 'Classes/**/*.{h,m,mm,swift,cpp}'
  s.static_framework = true
  s.dependency 'Flutter'
  s.dependency 'OpenCV2', '~> 4.3.0'
  s.platform = :ios, '13.0'
  s.frameworks = 'ARKit', 'AVFoundation', 'SceneKit', 'UIKit'
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386',
    'CLANG_CXX_LANGUAGE_STANDARD' => 'c++17',
    'GCC_SYMBOLS_PRIVATE_EXTERN' => 'NO'
  }
  s.swift_version = '5.0'
  s.resource_bundles = {
    'giro360_capture_privacy' => ['Resources/PrivacyInfo.xcprivacy']
  }
end
