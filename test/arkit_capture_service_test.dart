import 'package:flutter_test/flutter_test.dart';
import 'package:giro360_capture/giro360_capture.dart';

void main() {
  test('parses capture progress and selected video frame metadata', () {
    final status = Giro360CaptureStatus.fromMap(_statusMap(complete: true));

    expect(status.complete, isTrue);
    expect(status.activeLap, 2);
    expect(status.frames.single.binIndex, 0);
    expect(status.frames.single.qualityScore, 72.5);
    expect(status.videoPath, '/tmp/giro360_capture.mp4');
    expect(status.cameraCalibration.available, isTrue);
    expect(status.cameraCalibration.horizontalFovDegrees, 67.4);
    expect(status.loopClosure.poseReliable, isTrue);
    expect(status.loopClosure.visualInlierCount, 86);
    expect(status.frames.single.cameraImageWidth, 1920);
    expect(status.rotationSpeed, Giro360RotationSpeed.ideal);
    expect(status.currentAngularSpeedDegrees, closeTo(11.46, 0.01));
  });

  test('keeps the validated video refinement native contract stable', () {
    expect(GuidedAlignmentMode.videoRefine.nativeValue, 4);
    expect(GuidedAlignmentMode.videoRefine.label, 'ORB vídeo coerente');
  });

  test('parses a detailed Android compatibility report', () {
    final info = Giro360SupportInfo.fromMap({
      'platform': 'android',
      'supported': true,
      'ready': false,
      'recommendedMode': 'ar_tracked',
      'reason': 'Autorize a câmera.',
      'requirements': <Object?>[
        <Object?, Object?>{
          'id': 'gyroscope',
          'label': 'Giroscópio',
          'required': true,
          'state': 'available',
          'message': 'Disponível',
        },
        <Object?, Object?>{
          'id': 'camera_permission',
          'label': 'Permissão da câmera',
          'required': true,
          'state': 'permission_required',
          'message': 'Será solicitada',
        },
      ],
    });

    expect(info.supported, isTrue);
    expect(info.ready, isFalse);
    expect(info.recommendedMode, Giro360CaptureMode.arTracked);
    expect(info.canPrepare, isTrue);
    expect(info.blockingRequirements.single.id, 'camera_permission');
  });

  test('accepts video-only fallback with optional motion sensors', () {
    final info = Giro360SupportInfo.fromMap({
      'platform': 'android',
      'supported': true,
      'ready': true,
      'recommendedMode': 'video_only',
      'reason': 'Modo vídeo disponível.',
      'requirements': <Object?>[
        <Object?, Object?>{
          'id': 'gyroscope',
          'label': 'Giroscópio',
          'required': false,
          'state': 'missing',
          'message': 'Opcional no modo vídeo',
        },
        <Object?, Object?>{
          'id': 'camera_permission',
          'label': 'Permissão da câmera',
          'required': true,
          'state': 'available',
          'message': 'Autorizada',
        },
      ],
    });

    expect(info.supported, isTrue);
    expect(info.ready, isTrue);
    expect(info.recommendedMode, Giro360CaptureMode.videoOnly);
    expect(info.blockingRequirements, isEmpty);
  });
}

Map<Object?, Object?> _statusMap({required bool complete}) => {
      'running': !complete,
      'complete': complete,
      'message': complete ? 'ok' : 'capturing',
      'trackingState': 'normal',
      'direction': 'right',
      'progress': complete ? 1.0 : 0.5,
      'progressDegrees': complete ? 720.0 : 360.0,
      'completedLaps': complete ? 2 : 1,
      'requiredLaps': 2,
      'binCount': 30,
      'selectedCount': 1,
      'selectedLap': 2,
      'lapCandidateCounts': <Object?>[30, 30],
      'videoPath': '/tmp/giro360_capture.mp4',
      'videoTimelinePath': '/tmp/giro360_video_timeline.json',
      'captureSource': 'video',
      'currentAngularSpeed': 0.2,
      'rotationSpeed': 'ideal',
      'cameraCalibration': <Object?, Object?>{
        'available': true,
        'stable': true,
        'imageWidth': 1920,
        'imageHeight': 1080,
        'horizontalFovDegrees': 67.4,
        'verticalFovDegrees': 42.1,
        'focalLengthVariationPercent': 0.02,
        'principalPointDriftPercent': 0.01,
        'cameraCharacteristics': <Object?, Object?>{
          'cameraId': '0',
        },
      },
      'loopClosure': <Object?, Object?>{
        'matchedBinCount': 29,
        'meanTranslationMeters': 0.012,
        'maxTranslationMeters': 0.03,
        'meanRotationDegrees': 0.8,
        'maxRotationDegrees': 1.6,
        'poseReliable': true,
        'visualAnalyzed': true,
        'visualReferenceBin': 0,
        'visualRawMatchCount': 400,
        'visualGoodMatchCount': 120,
        'visualInlierCount': 86,
        'visualInlierRatio': 0.716,
        'visualSpatialCoverage': 0.75,
        'visualMeanReprojectionErrorPixels': 1.4,
        'visualReliable': true,
      },
      'frames': <Object?>[
        <Object?, Object?>{
          'binIndex': 0,
          'lapIndex': 1,
          'filePath': '/tmp/video_000.jpg',
          'targetYawRadians': 0.0,
          'relativeYawRadians': 0.01,
          'pitchRadians': -0.02,
          'rollRadians': 0.03,
          'translationMeters': 0.04,
          'qualityScore': 72.5,
          'sharpnessScore': 31.2,
          'angularSpeedRadiansPerSecond': 0.2,
          'centerErrorRadians': 0.01,
          'trackingState': 'normal',
          'capturedAt': '2026-07-21T01:02:03.000Z',
          'frameTimestampSeconds': 42.0,
          'videoTimeSeconds': 12.5,
          'cameraIntrinsics': <Object?>[1.0, 0.0, 2.0],
          'cameraTransform': <Object?>[1.0, 0.0, 0.0, 1.0],
          'cameraImageWidth': 1920,
          'cameraImageHeight': 1080,
          'selectionSource': 'captured',
        },
      ],
    };
