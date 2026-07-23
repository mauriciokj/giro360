#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <exception>
#include <iomanip>
#include <opencv2/calib3d.hpp>
#include <opencv2/core.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/stitching.hpp>
#include <opencv2/stitching/detail/blenders.hpp>
#include <opencv2/stitching/detail/seam_finders.hpp>
#include <opencv2/video/tracking.hpp>
#include <limits>
#include <sstream>
#include <string>
#include <vector>

#ifndef GIRO360_EXPERIMENT_GRAPH_CUT_MULTIBAND
#define GIRO360_EXPERIMENT_GRAPH_CUT_MULTIBAND 0
#endif

#ifndef GIRO360_EXPERIMENT_RELAX_TRUSTED_VERTICAL
#define GIRO360_EXPERIMENT_RELAX_TRUSTED_VERTICAL 0
#endif

#ifndef GIRO360_EXPERIMENT_LOCAL_MESH_WARP
#define GIRO360_EXPERIMENT_LOCAL_MESH_WARP 0
#endif

#ifndef GIRO360_EXPERIMENT_EXPANDED_OVERLAP
#define GIRO360_EXPERIMENT_EXPANDED_OVERLAP 0
#endif

namespace {

constexpr int kGuidedFallbackCode = 20;
constexpr int kMaxImagesForFeatureStitching = 12;
constexpr int kGuidedFillBlackBands = 0;
constexpr int kGuidedFillEdge = 1;
constexpr int kGuidedAlignmentTelemetryOnly = 0;
constexpr int kGuidedAlignmentHorizontalRefine = 1;
constexpr int kGuidedAlignmentLocalRefine = 2;
constexpr int kGuidedAlignmentAffineLocalRefine = 3;
constexpr int kGuidedAlignmentVideoRefine = 4;
constexpr int kGuidedRefineMinMatches = 8;
constexpr int kGuidedRefineMaxHorizontalPixels = 28;
constexpr int kGuidedClosureRefineMaxHorizontalPixels = 36;
constexpr int kGuidedClosureRefineMinFilteredMatches = 48;
constexpr int kGuidedRefineMaxVerticalPixels = 18;
constexpr int kGuidedRefineTrustedVerticalPixels = 6;
constexpr int kGuidedVerticalSmoothingThresholdPixels = 1;
constexpr int kGuidedGlobalOffsetMaxRiskScore = 64;
constexpr int kGuidedGlobalOffsetIterations = 80;
constexpr int kGuidedGlobalOffsetMaxHorizontalPixels = 38;
constexpr int kGuidedGlobalOffsetMaxVerticalPixels = 22;
constexpr int kGuidedClosureFallbackMaxHorizontalPixels = 24;
constexpr int kGuidedClosureFallbackMinHorizontalPixels = 3;
constexpr int kGuidedClosureConstraintMaxHorizontalPixels = 40;
constexpr int kGuidedAdaptiveSeamMinOverlapPixels = 48;
constexpr int kGuidedWeakSeamRiskScore = 42;
constexpr int kGuidedWeakSeamLowConfidenceCost = 32;
constexpr int kGuidedGraphCutRiskScore = 42;
constexpr int kGuidedMultiBandCount = 5;
constexpr int kGuidedMeshWarpGridColumns = 6;
constexpr int kGuidedMeshWarpGridRows = 10;
constexpr int kGuidedMeshWarpMaxHorizontalPixels = 14;
constexpr int kGuidedMeshWarpMaxVerticalPixels = 24;
constexpr double kGuidedMeshWarpMaxConsistencyError = 2.75;
constexpr double kGuidedMeshWarpMinReliableRatio = 0.08;
constexpr int kGuidedAffineMinInliers = 12;
constexpr int kGuidedAffineMinSpatialBins = 3;
constexpr int kGuidedAffineIterations = 80;
constexpr double kGuidedExposureGainMin = 0.88;
constexpr double kGuidedExposureGainMax = 1.14;
constexpr double kGuidedRefineTrustedHorizontalDamping = 0.35;
constexpr double kGuidedGlobalOffsetRelaxation = 0.35;
constexpr double kGuidedGlobalOffsetPrior = 0.012;
constexpr double kGuidedAffineMinInlierRatio = 0.55;
constexpr double kGuidedAffineMaxAbsRotationDegrees = 1.50;
constexpr double kGuidedAffineMinScale = 0.98;
constexpr double kGuidedAffineMaxScale = 1.02;
constexpr double kGuidedAffineMaxRmsErrorPixels = 3.0;
constexpr double kGuidedAffineRelaxation = 0.32;
constexpr double kGuidedAffinePrior = 0.018;
constexpr double kPi = 3.14159265358979323846;
constexpr double kTwoPi = kPi * 2.0;
constexpr double kGuidedTrustTelemetryMaxPitchRadians = kPi / 144.0;
constexpr float kWeightEpsilon = 1e-4F;

struct GuidedPatch {
  cv::Mat patch;
  int center_x = 0;
  int vertical_offset = 0;
  bool valid = false;
  int left_seam_x = -1;
  int right_seam_x = -1;
  int left_seam_blend_width = 0;
  int right_seam_blend_width = 0;
};

struct GuidedExposureStats {
  bool valid = false;
  double mean_b = 0.0;
  double mean_g = 0.0;
  double mean_r = 0.0;
  double luminance = 0.0;
};

struct GuidedRefinementPair {
  int from_index = 0;
  int to_index = 0;
  int raw_matches = 0;
  int filtered_matches = 0;
  int correction_x = 0;
  int correction_y = 0;
  bool refined = false;
  bool vertical_guarded = false;
  int guarded_correction_y = 0;
  int spatial_bin_count = 0;
  bool affine_attempted = false;
  bool affine_applied = false;
  int affine_inlier_count = 0;
  int affine_spatial_bin_count = 0;
  double affine_inlier_ratio = 0.0;
  double affine_rotation_degrees = 0.0;
  double affine_scale = 1.0;
  double affine_rms_error = 0.0;
  std::string affine_status;
  std::string reason;
};

struct GuidedRefinementMetrics {
  int pair_count = 0;
  int refined_pair_count = 0;
  int rejected_pair_count = 0;
  int invalid_geometry_count = 0;
  int insufficient_texture_count = 0;
  int insufficient_matches_count = 0;
  int insufficient_consensus_count = 0;
  int excessive_correction_count = 0;
  int zero_correction_count = 0;
  int total_abs_correction_x = 0;
  int total_abs_correction_y = 0;
  int max_abs_correction_x = 0;
  int max_abs_correction_y = 0;
  int vertical_smoothing_count = 0;
  int total_abs_vertical_smoothing = 0;
  int max_abs_vertical_smoothing = 0;
  bool vertical_telemetry_trusted = false;
  double vertical_telemetry_max_abs_pitch_degrees = 0.0;
  int vertical_telemetry_guard_count = 0;
  int total_abs_vertical_guard = 0;
  int max_abs_vertical_guard = 0;
  std::string alignment_mode = "horizontal_refine";
  bool alignment_refine_disabled = false;
  int output_roll_pixels = 0;
  std::string output_cut_seam;
  int output_cut_seam_risk_score = 0;
  int closure_seam_risk_score = 0;
  bool global_offset_optimization_enabled = false;
  int global_offset_trusted_pair_count = 0;
  bool global_offset_closure_used = false;
  bool global_offset_closure_fallback_used = false;
  int global_offset_closure_fallback_support_count = 0;
  int global_offset_closure_fallback_correction_x = 0;
  int global_offset_closure_spatial_bin_count = 0;
  bool global_offset_closure_candidate_rejected = false;
  double global_offset_closure_residual_improvement = 0.0;
  double global_offset_sequential_residual_increase = 0.0;
  int global_offset_applied_frame_count = 0;
  int total_abs_global_offset_x = 0;
  int total_abs_global_offset_y = 0;
  int max_abs_global_offset_x = 0;
  int max_abs_global_offset_y = 0;
  bool affine_refinement_enabled = false;
  int affine_pair_count = 0;
  int affine_applied_pair_count = 0;
  int affine_rejected_pair_count = 0;
  int affine_applied_frame_count = 0;
  double total_abs_affine_rotation_degrees = 0.0;
  double max_abs_affine_rotation_degrees = 0.0;
  double total_abs_affine_scale_delta = 0.0;
  double max_abs_affine_scale_delta = 0.0;
  double total_affine_rms_error = 0.0;
  double max_affine_rms_error = 0.0;
  bool adaptive_seam_blending_enabled = false;
  int adaptive_seam_count = 0;
  int adaptive_seam_low_confidence_count = 0;
  double total_adaptive_seam_cost = 0.0;
  double max_adaptive_seam_cost = 0.0;
  int total_abs_adaptive_seam_offset = 0;
  int max_abs_adaptive_seam_offset = 0;
  bool exposure_compensation_enabled = false;
  int exposure_compensated_patch_count = 0;
  double total_abs_exposure_gain_delta = 0.0;
  double max_abs_exposure_gain_delta = 0.0;
  double total_abs_color_gain_delta = 0.0;
  double max_abs_color_gain_delta = 0.0;
  bool weak_seam_softening_enabled = false;
  int weak_seam_softened_count = 0;
  int weak_closure_seam_softened_count = 0;
  int weak_seam_max_risk_score = 0;
  int weak_seam_total_blend_width = 0;
  int weak_seam_max_blend_width = 0;
  bool graph_cut_seam_enabled = false;
  int graph_cut_seam_count = 0;
  int graph_cut_seam_failed_count = 0;
  bool multiband_blending_enabled = false;
  int multiband_count = 0;
  bool local_mesh_warp_enabled = false;
  int local_mesh_warp_candidate_count = 0;
  int local_mesh_warp_applied_count = 0;
  int local_mesh_warp_rejected_count = 0;
  double total_local_mesh_warp_reliable_ratio = 0.0;
  double max_local_mesh_warp_displacement = 0.0;
  bool expanded_overlap_enabled = false;
  int expanded_overlap_source_crop_percent = 46;
  int expanded_overlap_patch_width = 0;
  int expanded_overlap_width = 0;
  std::vector<GuidedRefinementPair> pairs;
};

void write_error(char* error_buffer, int error_buffer_length, const std::string& message) {
  if (error_buffer == nullptr || error_buffer_length <= 0) {
    return;
  }

  std::snprintf(error_buffer, static_cast<size_t>(error_buffer_length), "%s", message.c_str());
}

std::vector<cv::Mat> load_images(const char** image_paths, int image_count) {
  std::vector<cv::Mat> images;
  images.reserve(static_cast<size_t>(image_count));

  for (int i = 0; i < image_count; ++i) {
    if (image_paths[i] == nullptr) {
      throw std::runtime_error("Caminho de imagem nulo recebido.");
    }

    cv::Mat image = cv::imread(image_paths[i], cv::IMREAD_COLOR);
    if (image.empty()) {
      throw std::runtime_error(std::string("Não consegui abrir a imagem: ") + image_paths[i]);
    }

    images.push_back(image);
  }

  return images;
}

std::string stitcher_status_to_string(cv::Stitcher::Status status) {
  switch (status) {
    case cv::Stitcher::OK:
      return "OK";
    case cv::Stitcher::ERR_NEED_MORE_IMGS:
      return "O OpenCV precisa de mais imagens com sobreposição suficiente.";
    case cv::Stitcher::ERR_HOMOGRAPHY_EST_FAIL:
      return "Falha ao estimar homografia. Capture mais textura e sobreposição.";
    case cv::Stitcher::ERR_CAMERA_PARAMS_ADJUST_FAIL:
      return "Falha ao ajustar os parâmetros da câmera.";
    default:
      return "Falha desconhecida no cv::Stitcher.";
  }
}

bool quick_orb_overlap_check(const std::vector<cv::Mat>& images, std::string* error) {
  if (images.size() < 2) {
    *error = "São necessárias pelo menos 2 imagens.";
    return false;
  }

  cv::Ptr<cv::ORB> orb = cv::ORB::create(2500);
  cv::BFMatcher matcher(cv::NORM_HAMMING);

  for (size_t i = 1; i < images.size(); ++i) {
    std::vector<cv::KeyPoint> keypoints_a;
    std::vector<cv::KeyPoint> keypoints_b;
    cv::Mat descriptors_a;
    cv::Mat descriptors_b;

    orb->detectAndCompute(images[i - 1], cv::noArray(), keypoints_a, descriptors_a);
    orb->detectAndCompute(images[i], cv::noArray(), keypoints_b, descriptors_b);

    if (descriptors_a.empty() || descriptors_b.empty()) {
      *error = "ORB não encontrou textura suficiente em uma das imagens.";
      return false;
    }

    std::vector<std::vector<cv::DMatch>> knn_matches;
    matcher.knnMatch(descriptors_a, descriptors_b, knn_matches, 2);

    std::vector<cv::DMatch> good_matches;
    for (const auto& match_pair : knn_matches) {
      if (match_pair.size() < 2) {
        continue;
      }
      if (match_pair[0].distance < 0.75F * match_pair[1].distance) {
        good_matches.push_back(match_pair[0]);
      }
    }

    if (good_matches.size() < 12) {
      *error = "Sobreposição insuficiente entre imagens consecutivas.";
      return false;
    }

    std::vector<cv::Point2f> points_a;
    std::vector<cv::Point2f> points_b;
    points_a.reserve(good_matches.size());
    points_b.reserve(good_matches.size());

    for (const auto& match : good_matches) {
      points_a.push_back(keypoints_a[static_cast<size_t>(match.queryIdx)].pt);
      points_b.push_back(keypoints_b[static_cast<size_t>(match.trainIdx)].pt);
    }

    cv::Mat inlier_mask;
    cv::Mat homography = cv::findHomography(points_b, points_a, cv::RANSAC, 3.0, inlier_mask);
    if (homography.empty()) {
      *error = "Não foi possível calcular a homografia inicial com ORB/RANSAC.";
      return false;
    }

    const int inliers = cv::countNonZero(inlier_mask);
    if (inliers < 10) {
      *error = "Poucos pontos ORB consistentes após RANSAC.";
      return false;
    }
  }

  return true;
}

double normalize_positive_angle(double radians) {
  double value = std::fmod(radians, kTwoPi);
  if (value < 0.0) {
    value += kTwoPi;
  }
  return value;
}

int positive_mod(int value, int modulo) {
  int result = value % modulo;
  if (result < 0) {
    result += modulo;
  }
  return result;
}

double median_value(std::vector<double> values) {
  if (values.empty()) {
    return 0.0;
  }

  const size_t middle = values.size() / 2;
  std::nth_element(values.begin(), values.begin() + middle, values.end());
  double median = values[middle];
  if (values.size() % 2 == 0) {
    std::nth_element(values.begin(), values.begin() + middle - 1, values.end());
    median = (median + values[middle - 1]) / 2.0;
  }
  return median;
}

bool estimate_guided_patch_exposure(
    const cv::Mat& patch,
    GuidedExposureStats* stats) {
  if (stats == nullptr || patch.empty() || patch.channels() != 3) {
    return false;
  }

  const int x0 = std::max(0, patch.cols / 8);
  const int x1 = std::min(patch.cols, patch.cols - x0);
  const int y0 = std::max(0, patch.rows / 10);
  const int y1 = std::min(patch.rows, patch.rows - y0);
  if (x1 <= x0 || y1 <= y0) {
    return false;
  }

  double sum_b = 0.0;
  double sum_g = 0.0;
  double sum_r = 0.0;
  int sample_count = 0;
  const int step = std::max(1, std::min(patch.cols, patch.rows) / 180);

  for (int y = y0; y < y1; y += step) {
    const cv::Vec3b* row = patch.ptr<cv::Vec3b>(y);
    for (int x = x0; x < x1; x += step) {
      const cv::Vec3b color = row[x];
      const double luminance =
          (0.114 * static_cast<double>(color[0])) +
          (0.587 * static_cast<double>(color[1])) +
          (0.299 * static_cast<double>(color[2]));
      if (luminance < 24.0 || luminance > 242.0) {
        continue;
      }
      sum_b += static_cast<double>(color[0]);
      sum_g += static_cast<double>(color[1]);
      sum_r += static_cast<double>(color[2]);
      sample_count += 1;
    }
  }

  if (sample_count < 128) {
    return false;
  }

  stats->mean_b = sum_b / static_cast<double>(sample_count);
  stats->mean_g = sum_g / static_cast<double>(sample_count);
  stats->mean_r = sum_r / static_cast<double>(sample_count);
  stats->luminance =
      (0.114 * stats->mean_b) +
      (0.587 * stats->mean_g) +
      (0.299 * stats->mean_r);
  stats->valid = stats->luminance > 1.0;
  return stats->valid;
}

void apply_guided_patch_gain(
    cv::Mat* patch,
    double gain_b,
    double gain_g,
    double gain_r) {
  if (patch == nullptr || patch->empty()) {
    return;
  }

  cv::Mat float_patch;
  patch->convertTo(float_patch, CV_32FC3);
  std::vector<cv::Mat> channels;
  cv::split(float_patch, channels);
  if (channels.size() != 3) {
    return;
  }

  channels[0] *= gain_b;
  channels[1] *= gain_g;
  channels[2] *= gain_r;
  cv::merge(channels, float_patch);
  float_patch.convertTo(*patch, CV_8UC3);
}

void apply_guided_exposure_compensation(
    std::vector<GuidedPatch>* patches,
    GuidedRefinementMetrics* refinement_metrics) {
  if (patches == nullptr || patches->size() < 2) {
    return;
  }

  std::vector<GuidedExposureStats> stats(patches->size());
  std::vector<double> valid_b;
  std::vector<double> valid_g;
  std::vector<double> valid_r;
  std::vector<double> valid_luminance;

  for (size_t i = 0; i < patches->size(); ++i) {
    GuidedPatch& patch = (*patches)[i];
    if (!patch.valid || patch.patch.empty()) {
      continue;
    }
    if (!estimate_guided_patch_exposure(patch.patch, &stats[i])) {
      continue;
    }
    valid_b.push_back(stats[i].mean_b);
    valid_g.push_back(stats[i].mean_g);
    valid_r.push_back(stats[i].mean_r);
    valid_luminance.push_back(stats[i].luminance);
  }

  if (valid_luminance.size() < 2) {
    return;
  }

  const double reference_b = median_value(valid_b);
  const double reference_g = median_value(valid_g);
  const double reference_r = median_value(valid_r);
  const double reference_luminance = median_value(valid_luminance);
  if (reference_b <= 1.0 ||
      reference_g <= 1.0 ||
      reference_r <= 1.0 ||
      reference_luminance <= 1.0) {
    return;
  }

  if (refinement_metrics != nullptr) {
    refinement_metrics->exposure_compensation_enabled = true;
  }

  for (size_t i = 0; i < patches->size(); ++i) {
    GuidedPatch& patch = (*patches)[i];
    const GuidedExposureStats& patch_stats = stats[i];
    if (!patch.valid || patch.patch.empty() || !patch_stats.valid) {
      continue;
    }

    const double gain_b = std::clamp(
        reference_b / std::max(1.0, patch_stats.mean_b),
        kGuidedExposureGainMin,
        kGuidedExposureGainMax);
    const double gain_g = std::clamp(
        reference_g / std::max(1.0, patch_stats.mean_g),
        kGuidedExposureGainMin,
        kGuidedExposureGainMax);
    const double gain_r = std::clamp(
        reference_r / std::max(1.0, patch_stats.mean_r),
        kGuidedExposureGainMin,
        kGuidedExposureGainMax);

    const double luminance_gain =
        (0.114 * gain_b) + (0.587 * gain_g) + (0.299 * gain_r);
    const double exposure_delta = std::abs(luminance_gain - 1.0);
    const double color_delta = std::max({
        std::abs(gain_b - luminance_gain),
        std::abs(gain_g - luminance_gain),
        std::abs(gain_r - luminance_gain),
    });

    if (exposure_delta < 0.01 && color_delta < 0.01) {
      continue;
    }

    apply_guided_patch_gain(&patch.patch, gain_b, gain_g, gain_r);
    if (refinement_metrics != nullptr) {
      refinement_metrics->exposure_compensated_patch_count += 1;
      refinement_metrics->total_abs_exposure_gain_delta += exposure_delta;
      refinement_metrics->max_abs_exposure_gain_delta = std::max(
          refinement_metrics->max_abs_exposure_gain_delta,
          exposure_delta);
      refinement_metrics->total_abs_color_gain_delta += color_delta;
      refinement_metrics->max_abs_color_gain_delta = std::max(
          refinement_metrics->max_abs_color_gain_delta,
          color_delta);
    }
  }
}

std::string guided_alignment_mode_name(int guided_alignment_mode) {
  switch (guided_alignment_mode) {
    case kGuidedAlignmentTelemetryOnly:
      return "telemetry_only";
    case kGuidedAlignmentLocalRefine:
      return "local_refine";
    case kGuidedAlignmentAffineLocalRefine:
      return "affine_local_refine";
    case kGuidedAlignmentVideoRefine:
      return "video_refine";
    case kGuidedAlignmentHorizontalRefine:
    default:
      return "horizontal_refine";
  }
}

bool should_trust_vertical_telemetry(
    const double* actual_pitches,
    int image_count,
    double* max_abs_pitch_degrees) {
  if (actual_pitches == nullptr || image_count <= 0) {
    return false;
  }

  double max_abs_pitch = 0.0;
  for (int i = 0; i < image_count; ++i) {
    if (!std::isfinite(actual_pitches[i])) {
      return false;
    }
    max_abs_pitch = std::max(max_abs_pitch, std::abs(actual_pitches[i]));
  }

  if (max_abs_pitch_degrees != nullptr) {
    *max_abs_pitch_degrees = max_abs_pitch * 180.0 / kPi;
  }
  return max_abs_pitch <= kGuidedTrustTelemetryMaxPitchRadians;
}

std::string format_guided_refinement_metrics(const GuidedRefinementMetrics& metrics) {
  std::ostringstream output;
  output << std::fixed << std::setprecision(2);

  const double average_abs_x = metrics.refined_pair_count == 0
      ? 0.0
      : static_cast<double>(metrics.total_abs_correction_x) /
          static_cast<double>(metrics.refined_pair_count);
  const double average_abs_y = metrics.refined_pair_count == 0
      ? 0.0
      : static_cast<double>(metrics.total_abs_correction_y) /
          static_cast<double>(metrics.refined_pair_count);

  output
      << "\nguided_refine_pair_count=" << metrics.pair_count
      << "\nguided_alignment_mode=" << metrics.alignment_mode
      << "\nguided_alignment_refine_disabled="
      << (metrics.alignment_refine_disabled ? "true" : "false")
      << "\nguided_refine_refined_pair_count=" << metrics.refined_pair_count
      << "\nguided_refine_rejected_pair_count=" << metrics.rejected_pair_count
      << "\nguided_refine_invalid_geometry_count=" << metrics.invalid_geometry_count
      << "\nguided_refine_insufficient_texture_count=" << metrics.insufficient_texture_count
      << "\nguided_refine_insufficient_matches_count=" << metrics.insufficient_matches_count
      << "\nguided_refine_insufficient_consensus_count="
      << metrics.insufficient_consensus_count
      << "\nguided_refine_excessive_correction_count="
      << metrics.excessive_correction_count
      << "\nguided_refine_zero_correction_count=" << metrics.zero_correction_count
      << "\nguided_refine_avg_abs_dx_px=" << average_abs_x
      << "\nguided_refine_avg_abs_dy_px=" << average_abs_y
      << "\nguided_refine_max_abs_dx_px=" << metrics.max_abs_correction_x
      << "\nguided_refine_max_abs_dy_px=" << metrics.max_abs_correction_y
      << "\nguided_vertical_smoothing_count=" << metrics.vertical_smoothing_count
      << "\nguided_vertical_smoothing_avg_abs_px="
      << (metrics.vertical_smoothing_count == 0
          ? 0.0
          : static_cast<double>(metrics.total_abs_vertical_smoothing) /
              static_cast<double>(metrics.vertical_smoothing_count))
      << "\nguided_vertical_smoothing_max_abs_px="
      << metrics.max_abs_vertical_smoothing
      << "\nguided_vertical_telemetry_trusted="
      << (metrics.vertical_telemetry_trusted ? "true" : "false")
      << "\nguided_vertical_telemetry_max_abs_pitch_deg="
      << metrics.vertical_telemetry_max_abs_pitch_degrees
      << "\nguided_vertical_telemetry_guard_count="
      << metrics.vertical_telemetry_guard_count
      << "\nguided_vertical_telemetry_guard_avg_abs_px="
      << (metrics.vertical_telemetry_guard_count == 0
          ? 0.0
          : static_cast<double>(metrics.total_abs_vertical_guard) /
              static_cast<double>(metrics.vertical_telemetry_guard_count))
      << "\nguided_vertical_telemetry_guard_max_abs_px="
      << metrics.max_abs_vertical_guard
      << "\nguided_global_offset_optimization_enabled="
      << (metrics.global_offset_optimization_enabled ? "true" : "false")
      << "\nguided_global_offset_trusted_pair_count="
      << metrics.global_offset_trusted_pair_count
      << "\nguided_global_offset_closure_used="
      << (metrics.global_offset_closure_used ? "true" : "false")
      << "\nguided_global_offset_closure_fallback_used="
      << (metrics.global_offset_closure_fallback_used ? "true" : "false")
      << "\nguided_global_offset_closure_fallback_support_count="
      << metrics.global_offset_closure_fallback_support_count
      << "\nguided_global_offset_closure_fallback_correction_x_px="
      << metrics.global_offset_closure_fallback_correction_x
      << "\nguided_global_offset_closure_spatial_bin_count="
      << metrics.global_offset_closure_spatial_bin_count
      << "\nguided_global_offset_closure_candidate_rejected="
      << (metrics.global_offset_closure_candidate_rejected ? "true" : "false")
      << "\nguided_global_offset_closure_residual_improvement_px="
      << metrics.global_offset_closure_residual_improvement
      << "\nguided_global_offset_sequential_residual_increase_px="
      << metrics.global_offset_sequential_residual_increase
      << "\nguided_global_offset_applied_frame_count="
      << metrics.global_offset_applied_frame_count
      << "\nguided_global_offset_avg_abs_x_px="
      << (metrics.global_offset_applied_frame_count == 0
          ? 0.0
          : static_cast<double>(metrics.total_abs_global_offset_x) /
              static_cast<double>(metrics.global_offset_applied_frame_count))
      << "\nguided_global_offset_avg_abs_y_px="
      << (metrics.global_offset_applied_frame_count == 0
          ? 0.0
          : static_cast<double>(metrics.total_abs_global_offset_y) /
              static_cast<double>(metrics.global_offset_applied_frame_count))
      << "\nguided_global_offset_max_abs_x_px="
      << metrics.max_abs_global_offset_x
      << "\nguided_global_offset_max_abs_y_px="
      << metrics.max_abs_global_offset_y
      << "\nguided_affine_refinement_enabled="
      << (metrics.affine_refinement_enabled ? "true" : "false")
      << "\nguided_affine_pair_count=" << metrics.affine_pair_count
      << "\nguided_affine_applied_pair_count="
      << metrics.affine_applied_pair_count
      << "\nguided_affine_rejected_pair_count="
      << metrics.affine_rejected_pair_count
      << "\nguided_affine_applied_frame_count="
      << metrics.affine_applied_frame_count
      << "\nguided_affine_avg_abs_rotation_deg="
      << (metrics.affine_applied_frame_count == 0
          ? 0.0
          : metrics.total_abs_affine_rotation_degrees /
              static_cast<double>(metrics.affine_applied_frame_count))
      << "\nguided_affine_max_abs_rotation_deg="
      << metrics.max_abs_affine_rotation_degrees
      << "\nguided_affine_avg_abs_scale_delta="
      << (metrics.affine_applied_frame_count == 0
          ? 0.0
          : metrics.total_abs_affine_scale_delta /
              static_cast<double>(metrics.affine_applied_frame_count))
      << "\nguided_affine_max_abs_scale_delta="
      << metrics.max_abs_affine_scale_delta
      << "\nguided_affine_avg_rms_error_px="
      << (metrics.affine_applied_pair_count == 0
          ? 0.0
          : metrics.total_affine_rms_error /
              static_cast<double>(metrics.affine_applied_pair_count))
      << "\nguided_affine_max_rms_error_px="
      << metrics.max_affine_rms_error
      << "\nguided_adaptive_seam_blending_enabled="
      << (metrics.adaptive_seam_blending_enabled ? "true" : "false")
      << "\nguided_adaptive_seam_count=" << metrics.adaptive_seam_count
      << "\nguided_adaptive_seam_low_confidence_count="
      << metrics.adaptive_seam_low_confidence_count
      << "\nguided_adaptive_seam_avg_cost="
      << (metrics.adaptive_seam_count == 0
          ? 0.0
          : metrics.total_adaptive_seam_cost /
              static_cast<double>(metrics.adaptive_seam_count))
      << "\nguided_adaptive_seam_max_cost="
      << metrics.max_adaptive_seam_cost
      << "\nguided_adaptive_seam_avg_offset_from_center_px="
      << (metrics.adaptive_seam_count == 0
          ? 0.0
          : static_cast<double>(metrics.total_abs_adaptive_seam_offset) /
              static_cast<double>(metrics.adaptive_seam_count))
      << "\nguided_adaptive_seam_max_offset_from_center_px="
      << metrics.max_abs_adaptive_seam_offset
      << "\nguided_exposure_compensation_enabled="
      << (metrics.exposure_compensation_enabled ? "true" : "false")
      << "\nguided_exposure_compensated_patch_count="
      << metrics.exposure_compensated_patch_count
      << "\nguided_exposure_avg_abs_gain_delta="
      << (metrics.exposure_compensated_patch_count == 0
          ? 0.0
          : metrics.total_abs_exposure_gain_delta /
              static_cast<double>(metrics.exposure_compensated_patch_count))
      << "\nguided_exposure_max_abs_gain_delta="
      << metrics.max_abs_exposure_gain_delta
      << "\nguided_color_avg_abs_gain_delta="
      << (metrics.exposure_compensated_patch_count == 0
          ? 0.0
          : metrics.total_abs_color_gain_delta /
              static_cast<double>(metrics.exposure_compensated_patch_count))
      << "\nguided_color_max_abs_gain_delta="
      << metrics.max_abs_color_gain_delta
      << "\nguided_weak_seam_softening_enabled="
      << (metrics.weak_seam_softening_enabled ? "true" : "false")
      << "\nguided_weak_seam_softened_count="
      << metrics.weak_seam_softened_count
      << "\nguided_weak_closure_seam_softened_count="
      << metrics.weak_closure_seam_softened_count
      << "\nguided_weak_seam_max_risk_score="
      << metrics.weak_seam_max_risk_score
      << "\nguided_weak_seam_avg_blend_width_px="
      << (metrics.weak_seam_softened_count == 0
          ? 0.0
          : static_cast<double>(metrics.weak_seam_total_blend_width) /
              static_cast<double>(metrics.weak_seam_softened_count))
      << "\nguided_weak_seam_max_blend_width_px="
      << metrics.weak_seam_max_blend_width
      << "\nguided_graph_cut_seam_enabled="
      << (metrics.graph_cut_seam_enabled ? "true" : "false")
      << "\nguided_graph_cut_seam_count="
      << metrics.graph_cut_seam_count
      << "\nguided_graph_cut_seam_failed_count="
      << metrics.graph_cut_seam_failed_count
      << "\nguided_multiband_blending_enabled="
      << (metrics.multiband_blending_enabled ? "true" : "false")
      << "\nguided_multiband_count=" << metrics.multiband_count
      << "\nguided_local_mesh_warp_enabled="
      << (metrics.local_mesh_warp_enabled ? "true" : "false")
      << "\nguided_local_mesh_warp_candidate_count="
      << metrics.local_mesh_warp_candidate_count
      << "\nguided_local_mesh_warp_applied_count="
      << metrics.local_mesh_warp_applied_count
      << "\nguided_local_mesh_warp_rejected_count="
      << metrics.local_mesh_warp_rejected_count
      << "\nguided_local_mesh_warp_avg_reliable_ratio="
      << (metrics.local_mesh_warp_candidate_count == 0
          ? 0.0
          : metrics.total_local_mesh_warp_reliable_ratio /
              static_cast<double>(metrics.local_mesh_warp_candidate_count))
      << "\nguided_local_mesh_warp_max_displacement_px="
      << metrics.max_local_mesh_warp_displacement
      << "\nguided_expanded_overlap_enabled="
      << (metrics.expanded_overlap_enabled ? "true" : "false")
      << "\nguided_expanded_overlap_source_crop_percent="
      << metrics.expanded_overlap_source_crop_percent
      << "\nguided_expanded_overlap_patch_width_px="
      << metrics.expanded_overlap_patch_width
      << "\nguided_expanded_overlap_width_px="
      << metrics.expanded_overlap_width
      << "\nguided_output_roll_px=" << metrics.output_roll_pixels
      << "\nguided_output_cut_seam=" << metrics.output_cut_seam
      << "\nguided_output_cut_seam_risk_score="
      << metrics.output_cut_seam_risk_score
      << "\nguided_closure_seam_risk_score="
      << metrics.closure_seam_risk_score;

  if (!metrics.pairs.empty()) {
    output << "\nguided_refine_pair_details=";
    for (size_t i = 0; i < metrics.pairs.size(); ++i) {
      const GuidedRefinementPair& pair = metrics.pairs[i];
      if (i > 0) {
        output << "|";
      }
      output << pair.from_index << "-" << pair.to_index
          << ":raw=" << pair.raw_matches
          << ",filtered=" << pair.filtered_matches
          << ",bins=" << pair.spatial_bin_count;
      if (pair.affine_attempted) {
        output << ",affine=" << pair.affine_status
            << ",affine_inliers=" << pair.affine_inlier_count
            << ",affine_bins=" << pair.affine_spatial_bin_count
            << ",affine_ratio=" << pair.affine_inlier_ratio
            << ",affine_rot=" << pair.affine_rotation_degrees
            << ",affine_scale=" << pair.affine_scale
            << ",affine_rms=" << pair.affine_rms_error;
      }
      if (pair.refined) {
        output << ",dx=" << pair.correction_x << ",dy=" << pair.correction_y;
        if (pair.vertical_guarded) {
          output << ",guarded_dy=" << pair.guarded_correction_y;
        }
      } else {
        if (pair.reason == "excessive_correction") {
          output << ",dx=" << pair.correction_x << ",dy=" << pair.correction_y;
        }
        output << ",skip=" << pair.reason;
      }
    }
  }

  return output.str();
}

void fill_uncovered_vertical_edges(cv::Mat* output, const cv::Mat& weights);

void smooth_guided_vertical_offsets(
    std::vector<GuidedPatch>* patches,
    GuidedRefinementMetrics* refinement_metrics) {
  if (patches == nullptr || patches->size() < 3) {
    return;
  }

  std::vector<int> original_offsets;
  original_offsets.reserve(patches->size());
  for (const GuidedPatch& patch : *patches) {
    original_offsets.push_back(patch.vertical_offset);
  }

  for (size_t i = 0; i < patches->size(); ++i) {
    const size_t previous_index = i == 0 ? patches->size() - 1 : i - 1;
    const size_t next_index = (i + 1) % patches->size();
    const double smoothed_value =
        (static_cast<double>(original_offsets[previous_index]) * 0.25) +
        (static_cast<double>(original_offsets[i]) * 0.50) +
        (static_cast<double>(original_offsets[next_index]) * 0.25);
    const int smoothed_offset = static_cast<int>(std::lround(smoothed_value));
    const int delta = smoothed_offset - original_offsets[i];

    if (std::abs(delta) < kGuidedVerticalSmoothingThresholdPixels) {
      continue;
    }

    (*patches)[i].vertical_offset = smoothed_offset;
    if (refinement_metrics != nullptr) {
      const int abs_delta = std::abs(delta);
      refinement_metrics->vertical_smoothing_count += 1;
      refinement_metrics->total_abs_vertical_smoothing += abs_delta;
      refinement_metrics->max_abs_vertical_smoothing = std::max(
          refinement_metrics->max_abs_vertical_smoothing,
          abs_delta);
    }
  }
}

void record_rejected_pair_metrics(
    const GuidedRefinementPair& pair_metrics,
    GuidedRefinementMetrics* refinement_metrics) {
  if (refinement_metrics == nullptr) {
    return;
  }

  refinement_metrics->rejected_pair_count += 1;
  if (pair_metrics.reason == "invalid_geometry" ||
      pair_metrics.reason == "invalid_patch") {
    refinement_metrics->invalid_geometry_count += 1;
  } else if (pair_metrics.reason == "insufficient_texture") {
    refinement_metrics->insufficient_texture_count += 1;
  } else if (pair_metrics.reason == "insufficient_matches") {
    refinement_metrics->insufficient_matches_count += 1;
  } else if (pair_metrics.reason == "insufficient_consensus") {
    refinement_metrics->insufficient_consensus_count += 1;
  } else if (pair_metrics.reason == "excessive_correction") {
    refinement_metrics->excessive_correction_count += 1;
  } else if (pair_metrics.reason == "zero_correction") {
    refinement_metrics->zero_correction_count += 1;
  }
  refinement_metrics->pairs.push_back(pair_metrics);
}

void record_refined_pair_metrics(
    const GuidedRefinementPair& pair_metrics,
    GuidedRefinementMetrics* refinement_metrics) {
  if (refinement_metrics == nullptr) {
    return;
  }

  refinement_metrics->refined_pair_count += 1;
  refinement_metrics->total_abs_correction_x += std::abs(pair_metrics.correction_x);
  refinement_metrics->total_abs_correction_y += std::abs(pair_metrics.correction_y);
  refinement_metrics->max_abs_correction_x = std::max(
      refinement_metrics->max_abs_correction_x,
      std::abs(pair_metrics.correction_x));
  refinement_metrics->max_abs_correction_y = std::max(
      refinement_metrics->max_abs_correction_y,
      std::abs(pair_metrics.correction_y));
  if (pair_metrics.vertical_guarded) {
    const int abs_guard = std::abs(pair_metrics.guarded_correction_y);
    refinement_metrics->vertical_telemetry_guard_count += 1;
    refinement_metrics->total_abs_vertical_guard += abs_guard;
    refinement_metrics->max_abs_vertical_guard = std::max(
        refinement_metrics->max_abs_vertical_guard,
        abs_guard);
  }
  refinement_metrics->pairs.push_back(pair_metrics);
}

int guided_match_spatial_bin_count(
    const std::vector<cv::Point2f>& points,
    int width,
    int height) {
  if (points.empty() || width <= 0 || height <= 0) {
    return 0;
  }

  std::array<bool, 8> occupied_bins{};
  for (const cv::Point2f& point : points) {
    const int horizontal_bin = std::clamp(
        static_cast<int>(std::floor((point.x / width) * 4.0F)),
        0,
        3);
    const int vertical_bin = std::clamp(
        static_cast<int>(std::floor((point.y / height) * 2.0F)),
        0,
        1);
    occupied_bins[static_cast<size_t>((vertical_bin * 4) + horizontal_bin)] =
        true;
  }
  return static_cast<int>(std::count(
      occupied_bins.begin(), occupied_bins.end(), true));
}

bool estimate_guided_affine_pair(
    const GuidedPatch& previous,
    const GuidedPatch& current,
    int panorama_width,
    int patch_width,
    bool is_closure_pair,
    GuidedRefinementPair* pair_metrics) {
  if (pair_metrics == nullptr) {
    return false;
  }
  pair_metrics->affine_attempted = true;

  if (!previous.valid || !current.valid || previous.patch.empty() ||
      current.patch.empty()) {
    pair_metrics->affine_status = "invalid_patch";
    return false;
  }

  const int previous_start_x = previous.center_x - (patch_width / 2);
  const int current_start_x = current.center_x - (patch_width / 2);
  int expected_delta_x = current_start_x - previous_start_x;
  while (expected_delta_x <= 0) {
    expected_delta_x += panorama_width;
  }
  while (expected_delta_x > panorama_width / 2) {
    expected_delta_x -= panorama_width;
  }
  if (expected_delta_x <= 0 || expected_delta_x >= patch_width - 48) {
    pair_metrics->affine_status = "invalid_geometry";
    return false;
  }

  const int overlap_width = patch_width - expected_delta_x;
  if (overlap_width < 48) {
    pair_metrics->affine_status = "invalid_geometry";
    return false;
  }

  const cv::Rect previous_overlap(
      expected_delta_x, 0, overlap_width, previous.patch.rows);
  const cv::Rect current_overlap(0, 0, overlap_width, current.patch.rows);
  cv::Mat previous_gray;
  cv::Mat current_gray;
  cv::cvtColor(
      previous.patch(previous_overlap), previous_gray, cv::COLOR_BGR2GRAY);
  cv::cvtColor(
      current.patch(current_overlap), current_gray, cv::COLOR_BGR2GRAY);

  cv::Ptr<cv::ORB> orb = cv::ORB::create(1000);
  std::vector<cv::KeyPoint> previous_keypoints;
  std::vector<cv::KeyPoint> current_keypoints;
  cv::Mat previous_descriptors;
  cv::Mat current_descriptors;
  orb->detectAndCompute(
      previous_gray,
      cv::noArray(),
      previous_keypoints,
      previous_descriptors);
  orb->detectAndCompute(
      current_gray,
      cv::noArray(),
      current_keypoints,
      current_descriptors);
  if (previous_descriptors.empty() || current_descriptors.empty()) {
    pair_metrics->affine_status = "insufficient_texture";
    return false;
  }

  cv::BFMatcher matcher(cv::NORM_HAMMING);
  std::vector<std::vector<cv::DMatch>> knn_matches;
  matcher.knnMatch(previous_descriptors, current_descriptors, knn_matches, 2);
  std::vector<cv::Point2f> previous_points;
  std::vector<cv::Point2f> current_points;
  for (const auto& match_pair : knn_matches) {
    if (match_pair.size() < 2 ||
        match_pair[0].distance >= 0.78F * match_pair[1].distance) {
      continue;
    }
    previous_points.push_back(
        previous_keypoints[static_cast<size_t>(match_pair[0].queryIdx)].pt +
        cv::Point2f(static_cast<float>(previous_overlap.x), 0.0F));
    current_points.push_back(
        current_keypoints[static_cast<size_t>(match_pair[0].trainIdx)].pt +
        cv::Point2f(static_cast<float>(current_overlap.x), 0.0F));
  }

  if (previous_points.size() < static_cast<size_t>(kGuidedAffineMinInliers)) {
    pair_metrics->affine_status = "insufficient_matches";
    return false;
  }

  cv::Mat inlier_mask;
  cv::Mat affine = cv::estimateAffinePartial2D(
      current_points,
      previous_points,
      inlier_mask,
      cv::RANSAC,
      2.5,
      2000,
      0.99,
      10);
  if (affine.empty() || affine.rows != 2 || affine.cols != 3 ||
      inlier_mask.empty()) {
    pair_metrics->affine_status = "estimate_failed";
    return false;
  }

  cv::Mat affine64;
  affine.convertTo(affine64, CV_64F);
  const double m00 = affine64.at<double>(0, 0);
  const double m01 = affine64.at<double>(0, 1);
  const double m10 = affine64.at<double>(1, 0);
  const double m11 = affine64.at<double>(1, 1);
  const double tx = affine64.at<double>(0, 2);
  const double ty = affine64.at<double>(1, 2);
  const double scale_x = std::hypot(m00, m10);
  const double scale_y = std::hypot(m01, m11);
  const double scale = (scale_x + scale_y) / 2.0;
  const double rotation_degrees = std::atan2(m10, m00) * 180.0 / kPi;

  int inlier_count = 0;
  double squared_error = 0.0;
  std::vector<cv::Point2f> inlier_positions;
  const unsigned char* inliers = inlier_mask.ptr<unsigned char>();
  for (size_t index = 0; index < previous_points.size(); ++index) {
    if (index >= inlier_mask.total() || inliers[index] == 0) {
      continue;
    }
    const cv::Point2f& source = current_points[index];
    const cv::Point2f& destination = previous_points[index];
    const double predicted_x = (m00 * source.x) + (m01 * source.y) + tx;
    const double predicted_y = (m10 * source.x) + (m11 * source.y) + ty;
    const double error_x = destination.x - predicted_x;
    const double error_y = destination.y - predicted_y;
    squared_error += (error_x * error_x) + (error_y * error_y);
    inlier_positions.push_back(cv::Point2f(
        destination.x - static_cast<float>(previous_overlap.x),
        destination.y));
    inlier_count += 1;
  }

  const double inlier_ratio = static_cast<double>(inlier_count) /
      static_cast<double>(std::max<size_t>(1, previous_points.size()));
  const double rms_error = inlier_count == 0
      ? std::numeric_limits<double>::max()
      : std::sqrt(squared_error / static_cast<double>(inlier_count));
  const int spatial_bins = guided_match_spatial_bin_count(
      inlier_positions,
      overlap_width,
      previous.patch.rows);

  pair_metrics->affine_inlier_count = inlier_count;
  pair_metrics->affine_spatial_bin_count = spatial_bins;
  pair_metrics->affine_inlier_ratio = inlier_ratio;
  pair_metrics->affine_rotation_degrees = rotation_degrees;
  pair_metrics->affine_scale = scale;
  pair_metrics->affine_rms_error = rms_error;

  if (inlier_count < kGuidedAffineMinInliers ||
      inlier_ratio < kGuidedAffineMinInlierRatio) {
    pair_metrics->affine_status = "low_inlier_support";
    return false;
  }
  const int minimum_spatial_bins =
      is_closure_pair ? kGuidedAffineMinSpatialBins + 1
                      : kGuidedAffineMinSpatialBins;
  if (spatial_bins < minimum_spatial_bins) {
    pair_metrics->affine_status = "poor_spatial_coverage";
    return false;
  }
  if (!std::isfinite(scale) || !std::isfinite(rotation_degrees) ||
      scale < kGuidedAffineMinScale || scale > kGuidedAffineMaxScale ||
      std::abs(rotation_degrees) > kGuidedAffineMaxAbsRotationDegrees) {
    pair_metrics->affine_status = "unsafe_transform";
    return false;
  }
  if (!std::isfinite(rms_error) ||
      rms_error > kGuidedAffineMaxRmsErrorPixels) {
    pair_metrics->affine_status = "high_residual";
    return false;
  }

  pair_metrics->affine_applied = true;
  pair_metrics->affine_status = "applied";
  return true;
}

double guided_affine_pair_weight(const GuidedRefinementPair& pair) {
  if (!pair.affine_applied) {
    return 0.0;
  }
  const double match_weight = std::clamp(
      static_cast<double>(pair.affine_inlier_count) / 40.0,
      0.30,
      1.0);
  const double coverage_weight = std::clamp(
      static_cast<double>(pair.affine_spatial_bin_count) / 5.0,
      0.45,
      1.0);
  const double closure_weight =
      pair.to_index == 0 && pair.from_index > pair.to_index ? 0.82 : 1.0;
  return match_weight * coverage_weight * pair.affine_inlier_ratio *
      closure_weight;
}

std::vector<double> solve_guided_affine_offsets(
    const std::vector<GuidedRefinementPair>& pairs,
    int patch_count,
    bool scale_axis) {
  std::vector<double> offsets(static_cast<size_t>(patch_count), 0.0);
  if (patch_count < 2) {
    return offsets;
  }

  const double minimum = scale_axis
      ? std::log(kGuidedAffineMinScale)
      : -kGuidedAffineMaxAbsRotationDegrees;
  const double maximum = scale_axis
      ? std::log(kGuidedAffineMaxScale)
      : kGuidedAffineMaxAbsRotationDegrees;
  for (int iteration = 0; iteration < kGuidedAffineIterations; ++iteration) {
    for (const GuidedRefinementPair& pair : pairs) {
      if (pair.from_index < 0 || pair.to_index < 0 ||
          pair.from_index >= patch_count || pair.to_index >= patch_count) {
        continue;
      }
      const double weight = guided_affine_pair_weight(pair);
      if (weight <= 0.0) {
        continue;
      }
      const double measurement = scale_axis
          ? std::log(pair.affine_scale)
          : pair.affine_rotation_degrees;
      const double current = offsets[static_cast<size_t>(pair.to_index)] -
          offsets[static_cast<size_t>(pair.from_index)];
      const double adjustment =
          (current - measurement) * weight * kGuidedAffineRelaxation;
      const double movable_count =
          (pair.from_index == 0 ? 0.0 : 1.0) +
          (pair.to_index == 0 ? 0.0 : 1.0);
      if (movable_count <= 0.0) {
        continue;
      }
      if (pair.from_index != 0) {
        offsets[static_cast<size_t>(pair.from_index)] +=
            adjustment / movable_count;
      }
      if (pair.to_index != 0) {
        offsets[static_cast<size_t>(pair.to_index)] -=
            adjustment / movable_count;
      }
    }
    for (int index = 1; index < patch_count; ++index) {
      offsets[static_cast<size_t>(index)] *= (1.0 - kGuidedAffinePrior);
      offsets[static_cast<size_t>(index)] = std::clamp(
          offsets[static_cast<size_t>(index)], minimum, maximum);
    }
  }
  return offsets;
}

void copy_guided_affine_diagnostics(
    const std::vector<GuidedRefinementPair>* affine_pairs,
    GuidedRefinementPair* pair_metrics) {
  if (affine_pairs == nullptr || pair_metrics == nullptr) {
    return;
  }
  for (const GuidedRefinementPair& affine_pair : *affine_pairs) {
    if (affine_pair.from_index != pair_metrics->from_index ||
        affine_pair.to_index != pair_metrics->to_index) {
      continue;
    }
    pair_metrics->affine_attempted = affine_pair.affine_attempted;
    pair_metrics->affine_applied = affine_pair.affine_applied;
    pair_metrics->affine_inlier_count = affine_pair.affine_inlier_count;
    pair_metrics->affine_spatial_bin_count =
        affine_pair.affine_spatial_bin_count;
    pair_metrics->affine_inlier_ratio = affine_pair.affine_inlier_ratio;
    pair_metrics->affine_rotation_degrees =
        affine_pair.affine_rotation_degrees;
    pair_metrics->affine_scale = affine_pair.affine_scale;
    pair_metrics->affine_rms_error = affine_pair.affine_rms_error;
    pair_metrics->affine_status = affine_pair.affine_status;
    return;
  }
}

std::vector<GuidedRefinementPair> apply_guided_affine_pose_refinement(
    std::vector<GuidedPatch>* patches,
    int panorama_width,
    int patch_width,
    GuidedRefinementMetrics* refinement_metrics) {
  std::vector<GuidedRefinementPair> affine_pairs;
  if (patches == nullptr || patches->size() < 2) {
    return affine_pairs;
  }

  const int patch_count = static_cast<int>(patches->size());
  affine_pairs.reserve(patches->size());
  for (int to_index = 1; to_index < patch_count; ++to_index) {
    GuidedRefinementPair pair;
    pair.from_index = to_index - 1;
    pair.to_index = to_index;
    estimate_guided_affine_pair(
        (*patches)[static_cast<size_t>(pair.from_index)],
        (*patches)[static_cast<size_t>(pair.to_index)],
        panorama_width,
        patch_width,
        false,
        &pair);
    affine_pairs.push_back(pair);
  }
  GuidedRefinementPair closure_pair;
  closure_pair.from_index = patch_count - 1;
  closure_pair.to_index = 0;
  estimate_guided_affine_pair(
      patches->back(),
      patches->front(),
      panorama_width,
      patch_width,
      true,
      &closure_pair);
  affine_pairs.push_back(closure_pair);

  if (refinement_metrics != nullptr) {
    refinement_metrics->affine_refinement_enabled = true;
    refinement_metrics->affine_pair_count =
        static_cast<int>(affine_pairs.size());
    for (const GuidedRefinementPair& pair : affine_pairs) {
      if (pair.affine_applied) {
        refinement_metrics->affine_applied_pair_count += 1;
        refinement_metrics->total_affine_rms_error += pair.affine_rms_error;
        refinement_metrics->max_affine_rms_error = std::max(
            refinement_metrics->max_affine_rms_error,
            pair.affine_rms_error);
      } else {
        refinement_metrics->affine_rejected_pair_count += 1;
      }
    }
  }

  const std::vector<double> rotation_offsets =
      solve_guided_affine_offsets(affine_pairs, patch_count, false);
  const std::vector<double> scale_offsets =
      solve_guided_affine_offsets(affine_pairs, patch_count, true);
  for (int index = 1; index < patch_count; ++index) {
    GuidedPatch& patch = (*patches)[static_cast<size_t>(index)];
    if (!patch.valid || patch.patch.empty()) {
      continue;
    }
    const double rotation_degrees =
        rotation_offsets[static_cast<size_t>(index)];
    const double scale = std::exp(scale_offsets[static_cast<size_t>(index)]);
    if (std::abs(rotation_degrees) < 0.01 && std::abs(scale - 1.0) < 0.0002) {
      continue;
    }

    const double radians = rotation_degrees * kPi / 180.0;
    const double cosine = std::cos(radians) * scale;
    const double sine = std::sin(radians) * scale;
    const double center_x = static_cast<double>(patch.patch.cols - 1) / 2.0;
    const double center_y = static_cast<double>(patch.patch.rows - 1) / 2.0;
    cv::Mat transform = (cv::Mat_<double>(2, 3) <<
        cosine,
        -sine,
        center_x - (cosine * center_x) + (sine * center_y),
        sine,
        cosine,
        center_y - (sine * center_x) - (cosine * center_y));
    cv::Mat warped;
    cv::warpAffine(
        patch.patch,
        warped,
        transform,
        patch.patch.size(),
        cv::INTER_LINEAR,
        cv::BORDER_REFLECT_101);
    patch.patch = warped;

    if (refinement_metrics != nullptr) {
      const double abs_rotation = std::abs(rotation_degrees);
      const double abs_scale_delta = std::abs(scale - 1.0);
      refinement_metrics->affine_applied_frame_count += 1;
      refinement_metrics->total_abs_affine_rotation_degrees += abs_rotation;
      refinement_metrics->max_abs_affine_rotation_degrees = std::max(
          refinement_metrics->max_abs_affine_rotation_degrees,
          abs_rotation);
      refinement_metrics->total_abs_affine_scale_delta += abs_scale_delta;
      refinement_metrics->max_abs_affine_scale_delta = std::max(
          refinement_metrics->max_abs_affine_scale_delta,
          abs_scale_delta);
    }
  }
  return affine_pairs;
}

bool estimate_overlap_refinement(
    const GuidedPatch& previous,
    const GuidedPatch& current,
    int panorama_width,
    int patch_width,
    bool trust_vertical_telemetry,
    bool relax_vertical_corrections,
    bool is_closure_pair,
    GuidedRefinementPair* pair_metrics) {
  if (pair_metrics == nullptr) {
    return false;
  }

  if (!previous.valid || !current.valid || previous.patch.empty() || current.patch.empty()) {
    pair_metrics->reason = "invalid_patch";
    return false;
  }

  const int previous_start_x = previous.center_x - (patch_width / 2);
  const int current_start_x = current.center_x - (patch_width / 2);
  int expected_delta_x = current_start_x - previous_start_x;
  while (expected_delta_x <= 0) {
    expected_delta_x += panorama_width;
  }
  while (expected_delta_x > panorama_width / 2) {
    expected_delta_x -= panorama_width;
  }

  if (expected_delta_x <= 0 || expected_delta_x >= patch_width - 48) {
    pair_metrics->reason = "invalid_geometry";
    return false;
  }

  const int overlap_width = patch_width - expected_delta_x;
  if (overlap_width < 48) {
    pair_metrics->reason = "invalid_geometry";
    return false;
  }

  const cv::Rect previous_overlap(
      expected_delta_x,
      0,
      overlap_width,
      previous.patch.rows);
  const cv::Rect current_overlap(0, 0, overlap_width, current.patch.rows);

  cv::Mat previous_gray;
  cv::Mat current_gray;
  cv::cvtColor(previous.patch(previous_overlap), previous_gray, cv::COLOR_BGR2GRAY);
  cv::cvtColor(current.patch(current_overlap), current_gray, cv::COLOR_BGR2GRAY);

  cv::Ptr<cv::ORB> orb = cv::ORB::create(800);
  std::vector<cv::KeyPoint> previous_keypoints;
  std::vector<cv::KeyPoint> current_keypoints;
  cv::Mat previous_descriptors;
  cv::Mat current_descriptors;
  orb->detectAndCompute(previous_gray, cv::noArray(), previous_keypoints, previous_descriptors);
  orb->detectAndCompute(current_gray, cv::noArray(), current_keypoints, current_descriptors);

  if (previous_descriptors.empty() || current_descriptors.empty()) {
    pair_metrics->reason = "insufficient_texture";
    return false;
  }

  cv::BFMatcher matcher(cv::NORM_HAMMING);
  std::vector<std::vector<cv::DMatch>> knn_matches;
  matcher.knnMatch(previous_descriptors, current_descriptors, knn_matches, 2);

  std::vector<double> horizontal_corrections;
  std::vector<double> vertical_corrections;
  std::vector<double> match_positions_x;
  std::vector<double> match_positions_y;

  for (const auto& match_pair : knn_matches) {
    if (match_pair.size() < 2) {
      continue;
    }
    if (match_pair[0].distance >= 0.78F * match_pair[1].distance) {
      continue;
    }

    const cv::Point2f previous_point =
        previous_keypoints[static_cast<size_t>(match_pair[0].queryIdx)].pt +
        cv::Point2f(static_cast<float>(previous_overlap.x), 0.0F);
    const cv::Point2f current_point =
        current_keypoints[static_cast<size_t>(match_pair[0].trainIdx)].pt +
        cv::Point2f(static_cast<float>(current_overlap.x), 0.0F);

    const double correction_x_value =
        previous_point.x - current_point.x - expected_delta_x;
    const double correction_y_value =
        previous.vertical_offset + previous_point.y -
        current_point.y - current.vertical_offset;

    if (std::abs(correction_x_value) <= kGuidedRefineMaxHorizontalPixels * 2 &&
        std::abs(correction_y_value) <= kGuidedRefineMaxVerticalPixels * 2) {
      horizontal_corrections.push_back(correction_x_value);
      vertical_corrections.push_back(correction_y_value);
      match_positions_x.push_back(previous_point.x - previous_overlap.x);
      match_positions_y.push_back(previous_point.y);
    }
  }

  pair_metrics->raw_matches = static_cast<int>(horizontal_corrections.size());

  if (horizontal_corrections.size() < kGuidedRefineMinMatches ||
      vertical_corrections.size() < kGuidedRefineMinMatches) {
    pair_metrics->reason = "insufficient_matches";
    return false;
  }

  const double median_x = median_value(horizontal_corrections);
  const double median_y = median_value(vertical_corrections);

  std::vector<double> filtered_x;
  std::vector<double> filtered_y;
  std::vector<double> filtered_positions_x;
  std::vector<double> filtered_positions_y;
  for (size_t i = 0; i < horizontal_corrections.size(); ++i) {
    if (std::abs(horizontal_corrections[i] - median_x) <= 10.0 &&
        std::abs(vertical_corrections[i] - median_y) <= 14.0) {
      filtered_x.push_back(horizontal_corrections[i]);
      filtered_y.push_back(vertical_corrections[i]);
      filtered_positions_x.push_back(match_positions_x[i]);
      filtered_positions_y.push_back(match_positions_y[i]);
    }
  }

  pair_metrics->filtered_matches = static_cast<int>(filtered_x.size());

  if (filtered_x.size() < kGuidedRefineMinMatches ||
      filtered_y.size() < kGuidedRefineMinMatches) {
    pair_metrics->reason = "insufficient_consensus";
    return false;
  }

  std::array<bool, 8> occupied_bins{};
  for (size_t i = 0; i < filtered_positions_x.size(); ++i) {
    const int horizontal_bin = std::clamp(
        static_cast<int>(std::floor(
            (filtered_positions_x[i] / std::max(1, overlap_width)) * 4.0)),
        0,
        3);
    const int vertical_bin = std::clamp(
        static_cast<int>(std::floor(
            (filtered_positions_y[i] / std::max(1, previous.patch.rows)) *
            2.0)),
        0,
        1);
    occupied_bins[static_cast<size_t>((vertical_bin * 4) + horizontal_bin)] =
        true;
  }
  pair_metrics->spatial_bin_count = static_cast<int>(std::count(
      occupied_bins.begin(),
      occupied_bins.end(),
      true));

  const int raw_correction_x =
      static_cast<int>(std::lround(median_value(filtered_x)));
  const int raw_correction_y =
      static_cast<int>(std::lround(median_value(filtered_y)));

  const int vertical_limit = trust_vertical_telemetry
      ? (relax_vertical_corrections || GIRO360_EXPERIMENT_RELAX_TRUSTED_VERTICAL
          ? kGuidedRefineMaxVerticalPixels + 6
          : kGuidedRefineTrustedVerticalPixels)
      : kGuidedRefineMaxVerticalPixels;
  const bool has_strong_closure_support =
      is_closure_pair &&
      static_cast<int>(filtered_x.size()) >=
          kGuidedClosureRefineMinFilteredMatches;
  const int horizontal_limit = has_strong_closure_support
      ? kGuidedClosureRefineMaxHorizontalPixels
      : kGuidedRefineMaxHorizontalPixels;

  if (std::abs(raw_correction_x) > horizontal_limit) {
    pair_metrics->correction_x = raw_correction_x;
    pair_metrics->correction_y = raw_correction_y;
    pair_metrics->reason = "excessive_correction";
    return false;
  }
  if (!trust_vertical_telemetry &&
      std::abs(raw_correction_y) > vertical_limit) {
    pair_metrics->correction_x = raw_correction_x;
    pair_metrics->correction_y = raw_correction_y;
    pair_metrics->reason = "excessive_correction";
    return false;
  }

  int correction_x = raw_correction_x;
  if (trust_vertical_telemetry) {
    correction_x = static_cast<int>(
        std::lround(static_cast<double>(raw_correction_x) *
                    kGuidedRefineTrustedHorizontalDamping));
  }

  pair_metrics->correction_x = correction_x;
  if (std::abs(raw_correction_y) > vertical_limit) {
    pair_metrics->vertical_guarded = trust_vertical_telemetry;
    pair_metrics->guarded_correction_y = raw_correction_y;
    pair_metrics->correction_y = 0;
  } else {
    pair_metrics->correction_y = raw_correction_y;
  }

  if (pair_metrics->correction_x == 0 && pair_metrics->correction_y == 0) {
    pair_metrics->reason = "zero_correction";
    return false;
  }

  pair_metrics->refined = true;
  pair_metrics->reason = "refined";
  return true;
}

int guided_pair_risk_score(const GuidedRefinementPair& pair) {
  double risk = 0.0;

  if (!pair.refined) {
    if (pair.reason == "excessive_correction") {
      risk += 70.0;
    } else if (pair.reason == "invalid_geometry") {
      risk += 62.0;
    } else if (pair.reason == "insufficient_texture") {
      risk += 50.0;
    } else if (pair.reason == "insufficient_matches") {
      risk += 46.0;
    } else if (pair.reason == "insufficient_consensus") {
      risk += 42.0;
    } else if (pair.reason == "zero_correction") {
      risk += 18.0;
    } else {
      risk += 36.0;
    }

    if (std::abs(pair.correction_x) >= 24) {
      risk += 8.0;
    }
    if (std::abs(pair.correction_y) >= 14) {
      risk += 10.0;
    }
  } else {
    const int abs_x = std::abs(pair.correction_x);
    const int abs_y = std::abs(pair.correction_y);
    const int guarded_y = std::abs(pair.guarded_correction_y);
    if (abs_x >= 8) {
      risk += static_cast<double>(std::min(abs_x - 8, 28)) * 1.1;
    }
    if (abs_y >= 6) {
      risk += static_cast<double>(std::min(abs_y - 6, 24)) * 1.5;
    }
    if (guarded_y >= 8) {
      risk += static_cast<double>(std::min(guarded_y - 8, 32)) * 1.3;
    }
  }

  if (pair.raw_matches > 0 && pair.raw_matches < 45) {
    risk += 12.0;
  }
  if (pair.filtered_matches > 0 && pair.filtered_matches < 24) {
    risk += 14.0;
  }
  if (pair.to_index == 0 && pair.from_index > pair.to_index && risk >= 18.0) {
    risk += 6.0;
  }

  return std::clamp(static_cast<int>(std::lround(risk)), 0, 100);
}

const GuidedRefinementPair* find_guided_pair_metrics(
    const GuidedRefinementMetrics* refinement_metrics,
    int from_index,
    int to_index) {
  if (refinement_metrics == nullptr) {
    return nullptr;
  }

  for (const GuidedRefinementPair& pair : refinement_metrics->pairs) {
    if (pair.from_index == from_index && pair.to_index == to_index) {
      return &pair;
    }
  }
  return nullptr;
}

int guided_softened_seam_blend_width(
    int base_blend_width,
    int patch_width,
    int seam_risk,
    double seam_cost,
    bool is_closure_seam,
    bool has_refinement_support) {
  const double risk_factor = std::clamp(
      static_cast<double>(seam_risk - kGuidedWeakSeamRiskScore) / 36.0,
      0.0,
      1.0);
  const double cost_factor = std::clamp(
      (seam_cost - static_cast<double>(kGuidedWeakSeamLowConfidenceCost)) /
          28.0,
      0.0,
      1.0);
  double multiplier = 1.12 + (0.55 * std::max(risk_factor, cost_factor));
  if (is_closure_seam) {
    multiplier += 0.16;
  }
  if (!has_refinement_support) {
    multiplier += 0.12;
  }

  return std::clamp(
      static_cast<int>(
          std::lround(static_cast<double>(base_blend_width) * multiplier)),
      std::max(12, base_blend_width),
      std::max(12, patch_width / 2));
}

double guided_pair_global_offset_weight(const GuidedRefinementPair& pair) {
  if (!pair.refined) {
    return 0.0;
  }

  const int risk = guided_pair_risk_score(pair);
  if (risk > kGuidedGlobalOffsetMaxRiskScore) {
    return 0.0;
  }

  const double match_weight = pair.filtered_matches <= 0
      ? 0.35
      : std::clamp(static_cast<double>(pair.filtered_matches) / 36.0, 0.35, 1.0);
  const double risk_weight = std::clamp(
      static_cast<double>(kGuidedGlobalOffsetMaxRiskScore - risk + 12) /
          static_cast<double>(kGuidedGlobalOffsetMaxRiskScore + 12),
      0.20,
      1.0);
  const double closure_weight =
      pair.to_index == 0 && pair.from_index > pair.to_index ? 0.82 : 1.0;

  return match_weight * risk_weight * closure_weight;
}

std::vector<double> solve_guided_global_offsets(
    const std::vector<GuidedRefinementPair>& pairs,
    int patch_count,
    bool vertical_axis) {
  std::vector<double> offsets(static_cast<size_t>(patch_count), 0.0);
  if (patch_count < 2) {
    return offsets;
  }

  const double max_abs_offset = static_cast<double>(
      vertical_axis
          ? kGuidedGlobalOffsetMaxVerticalPixels
          : kGuidedGlobalOffsetMaxHorizontalPixels);

  for (int iteration = 0; iteration < kGuidedGlobalOffsetIterations; ++iteration) {
    for (const GuidedRefinementPair& pair : pairs) {
      if (pair.from_index < 0 ||
          pair.to_index < 0 ||
          pair.from_index >= patch_count ||
          pair.to_index >= patch_count) {
        continue;
      }
      if (vertical_axis && pair.reason == "synthetic_closure") {
        continue;
      }

      double weight = guided_pair_global_offset_weight(pair);
      if (vertical_axis && pair.vertical_guarded) {
        weight *= 0.25;
      }
      if (weight <= 0.0) {
        continue;
      }

      const double measurement = static_cast<double>(
          vertical_axis ? pair.correction_y : pair.correction_x);
      const double current =
          offsets[static_cast<size_t>(pair.to_index)] -
          offsets[static_cast<size_t>(pair.from_index)];
      const double error = current - measurement;
      const double adjustment =
          error * weight * kGuidedGlobalOffsetRelaxation;

      const double movable_count =
          (pair.from_index == 0 ? 0.0 : 1.0) +
          (pair.to_index == 0 ? 0.0 : 1.0);
      if (movable_count <= 0.0) {
        continue;
      }

      if (pair.from_index != 0) {
        offsets[static_cast<size_t>(pair.from_index)] +=
            adjustment / movable_count;
      }
      if (pair.to_index != 0) {
        offsets[static_cast<size_t>(pair.to_index)] -=
            adjustment / movable_count;
      }
    }

    for (int index = 1; index < patch_count; ++index) {
      offsets[static_cast<size_t>(index)] *=
          (1.0 - kGuidedGlobalOffsetPrior);
      offsets[static_cast<size_t>(index)] = std::clamp(
          offsets[static_cast<size_t>(index)],
          -max_abs_offset,
          max_abs_offset);
    }
  }

  return offsets;
}

double guided_average_sequential_residual(
    const std::vector<GuidedRefinementPair>& pairs,
    const std::vector<double>& offsets) {
  double weighted_residual = 0.0;
  double total_weight = 0.0;
  for (const GuidedRefinementPair& pair : pairs) {
    if (pair.to_index != pair.from_index + 1 ||
        pair.from_index < 0 ||
        pair.to_index < 0 ||
        pair.from_index >= static_cast<int>(offsets.size()) ||
        pair.to_index >= static_cast<int>(offsets.size())) {
      continue;
    }
    const double weight = guided_pair_global_offset_weight(pair);
    if (weight <= 0.0) {
      continue;
    }
    const double current =
        offsets[static_cast<size_t>(pair.to_index)] -
        offsets[static_cast<size_t>(pair.from_index)];
    weighted_residual +=
        std::abs(current - static_cast<double>(pair.correction_x)) * weight;
    total_weight += weight;
  }
  return total_weight <= 0.0 ? 0.0 : weighted_residual / total_weight;
}

double guided_closure_residual(
    const GuidedRefinementPair& closure_pair,
    const std::vector<double>& offsets) {
  if (closure_pair.from_index < 0 ||
      closure_pair.to_index < 0 ||
      closure_pair.from_index >= static_cast<int>(offsets.size()) ||
      closure_pair.to_index >= static_cast<int>(offsets.size())) {
    return std::numeric_limits<double>::max();
  }
  const double current =
      offsets[static_cast<size_t>(closure_pair.to_index)] -
      offsets[static_cast<size_t>(closure_pair.from_index)];
  return std::abs(
      current - static_cast<double>(closure_pair.correction_x));
}

bool validate_guided_closure_candidate(
    const std::vector<GuidedRefinementPair>& measured_pairs,
    const GuidedRefinementPair& closure_candidate,
    const std::vector<double>& base_offsets,
    const std::vector<double>& candidate_offsets,
    bool strong_candidate,
    double* closure_improvement,
    double* sequential_increase) {
  const double base_closure_residual =
      guided_closure_residual(closure_candidate, base_offsets);
  const double candidate_closure_residual =
      guided_closure_residual(closure_candidate, candidate_offsets);
  const double base_sequential_residual =
      guided_average_sequential_residual(measured_pairs, base_offsets);
  const double candidate_sequential_residual =
      guided_average_sequential_residual(measured_pairs, candidate_offsets);

  *closure_improvement = base_closure_residual - candidate_closure_residual;
  *sequential_increase =
      candidate_sequential_residual - base_sequential_residual;

  const double minimum_closure_improvement = strong_candidate ? 6.0 : 3.0;
  const double maximum_sequential_increase = strong_candidate
      ? std::max(2.0, base_sequential_residual * 0.40)
      : std::max(3.0, base_sequential_residual * 0.60);
  return *closure_improvement >= minimum_closure_improvement &&
      *sequential_increase <= maximum_sequential_increase;
}

bool estimate_guided_closure_fallback_pair(
    const std::vector<GuidedRefinementPair>& pairs,
    int patch_count,
    GuidedRefinementPair* closure_pair,
    int* support_count) {
  if (closure_pair == nullptr || support_count == nullptr || patch_count < 3) {
    return false;
  }

  double weighted_horizontal_sum = 0.0;
  double total_weight = 0.0;
  int trusted_sequential_count = 0;

  for (const GuidedRefinementPair& pair : pairs) {
    if (pair.from_index < 0 ||
        pair.to_index < 0 ||
        pair.from_index >= patch_count ||
        pair.to_index >= patch_count ||
        pair.to_index != pair.from_index + 1) {
      continue;
    }

    const double weight = guided_pair_global_offset_weight(pair);
    if (weight <= 0.0) {
      continue;
    }

    weighted_horizontal_sum += static_cast<double>(pair.correction_x) * weight;
    total_weight += weight;
    trusted_sequential_count += 1;
  }

  const int min_support = std::max(4, patch_count / 2);
  if (trusted_sequential_count < min_support || total_weight <= 0.0) {
    return false;
  }

  const double average_weight =
      total_weight / static_cast<double>(trusted_sequential_count);
  const double estimated_horizontal_drift =
      weighted_horizontal_sum / std::max(average_weight, 0.10);
  const int correction_x = std::clamp(
      static_cast<int>(std::lround(-estimated_horizontal_drift)),
      -kGuidedClosureFallbackMaxHorizontalPixels,
      kGuidedClosureFallbackMaxHorizontalPixels);
  if (std::abs(correction_x) < kGuidedClosureFallbackMinHorizontalPixels) {
    return false;
  }

  *closure_pair = GuidedRefinementPair{};
  closure_pair->from_index = patch_count - 1;
  closure_pair->to_index = 0;
  closure_pair->raw_matches = std::min(72, trusted_sequential_count * 4);
  closure_pair->filtered_matches = closure_pair->raw_matches;
  closure_pair->correction_x = correction_x;
  closure_pair->correction_y = 0;
  closure_pair->refined = true;
  closure_pair->reason = "synthetic_closure";
  *support_count = trusted_sequential_count;
  return true;
}

bool estimate_guided_strong_closure_constraint_pair(
    const std::vector<GuidedRefinementPair>& pairs,
    int patch_count,
    bool trust_vertical_telemetry,
    GuidedRefinementPair* closure_pair,
    int* support_count) {
  if (closure_pair == nullptr || support_count == nullptr || patch_count < 3) {
    return false;
  }

  for (const GuidedRefinementPair& pair : pairs) {
    const bool is_closure_pair =
        pair.from_index == patch_count - 1 && pair.to_index == 0;
    if (!is_closure_pair ||
        pair.refined ||
        pair.reason != "excessive_correction" ||
        pair.filtered_matches < kGuidedClosureRefineMinFilteredMatches ||
        pair.spatial_bin_count < 4 ||
        std::abs(pair.correction_y) > kGuidedRefineMaxVerticalPixels) {
      continue;
    }

    const double correction_scale = trust_vertical_telemetry
        ? kGuidedRefineTrustedHorizontalDamping
        : 1.0;
    const int correction_x = std::clamp(
        static_cast<int>(std::lround(
            static_cast<double>(pair.correction_x) * correction_scale)),
        -kGuidedClosureConstraintMaxHorizontalPixels,
        kGuidedClosureConstraintMaxHorizontalPixels);
    if (std::abs(correction_x) < kGuidedClosureFallbackMinHorizontalPixels) {
      return false;
    }

    *closure_pair = GuidedRefinementPair{};
    closure_pair->from_index = patch_count - 1;
    closure_pair->to_index = 0;
    closure_pair->raw_matches = pair.raw_matches;
    closure_pair->filtered_matches = pair.filtered_matches;
    closure_pair->correction_x = correction_x;
    closure_pair->correction_y = 0;
    closure_pair->refined = true;
    closure_pair->spatial_bin_count = pair.spatial_bin_count;
    closure_pair->reason = "synthetic_closure";
    *support_count = pair.filtered_matches;
    return true;
  }

  return false;
}

void apply_guided_global_offset_optimization(
    std::vector<GuidedPatch>* patches,
    const std::vector<GuidedRefinementPair>& pairs,
    int panorama_width,
    bool trust_vertical_telemetry,
    GuidedRefinementMetrics* refinement_metrics) {
  if (patches == nullptr || patches->size() < 2 || pairs.empty()) {
    return;
  }

  int trusted_pair_count = 0;
  bool closure_used = false;
  bool closure_fallback_used = false;
  int closure_fallback_support_count = 0;
  int closure_fallback_correction_x = 0;
  std::vector<GuidedRefinementPair> optimization_pairs = pairs;
  for (const GuidedRefinementPair& pair : pairs) {
    if (guided_pair_global_offset_weight(pair) <= 0.0) {
      continue;
    }
    trusted_pair_count += 1;
    if (pair.to_index == 0 && pair.from_index > pair.to_index) {
      closure_used = true;
    }
  }

  if (trusted_pair_count == 0) {
    return;
  }

  const int patch_count = static_cast<int>(patches->size());
  std::vector<double> horizontal_offsets =
      solve_guided_global_offsets(pairs, patch_count, false);
  const std::vector<double> vertical_offsets =
      solve_guided_global_offsets(pairs, patch_count, true);

  if (!closure_used) {
    GuidedRefinementPair fallback_closure_pair;
    int fallback_support_count = 0;
    const bool has_strong_candidate =
        estimate_guided_strong_closure_constraint_pair(
            pairs,
            patch_count,
            trust_vertical_telemetry,
            &fallback_closure_pair,
            &fallback_support_count);
    const bool has_fallback_candidate = has_strong_candidate ||
        estimate_guided_closure_fallback_pair(
            pairs, patch_count, &fallback_closure_pair, &fallback_support_count);
    if (has_fallback_candidate) {
      optimization_pairs.push_back(fallback_closure_pair);
      const std::vector<double> candidate_offsets =
          solve_guided_global_offsets(optimization_pairs, patch_count, false);
      double closure_improvement = 0.0;
      double sequential_increase = 0.0;
      const bool candidate_accepted = validate_guided_closure_candidate(
          pairs,
          fallback_closure_pair,
          horizontal_offsets,
          candidate_offsets,
          has_strong_candidate,
          &closure_improvement,
          &sequential_increase);
      if (refinement_metrics != nullptr) {
        refinement_metrics->global_offset_closure_spatial_bin_count =
            fallback_closure_pair.spatial_bin_count;
        refinement_metrics->global_offset_closure_residual_improvement =
            closure_improvement;
        refinement_metrics->global_offset_sequential_residual_increase =
            sequential_increase;
        refinement_metrics->global_offset_closure_candidate_rejected =
            !candidate_accepted;
      }
      if (candidate_accepted) {
        horizontal_offsets = candidate_offsets;
        trusted_pair_count += 1;
        closure_used = true;
        closure_fallback_used = true;
        closure_fallback_support_count = fallback_support_count;
        closure_fallback_correction_x = fallback_closure_pair.correction_x;
      }
    }
  }

  if (refinement_metrics != nullptr) {
    refinement_metrics->global_offset_optimization_enabled = true;
    refinement_metrics->global_offset_trusted_pair_count = trusted_pair_count;
    refinement_metrics->global_offset_closure_used = closure_used;
    refinement_metrics->global_offset_closure_fallback_used =
        closure_fallback_used;
    refinement_metrics->global_offset_closure_fallback_support_count =
        closure_fallback_support_count;
    refinement_metrics->global_offset_closure_fallback_correction_x =
        closure_fallback_correction_x;
  }

  for (int index = 1; index < patch_count; ++index) {
    const int offset_x = static_cast<int>(
        std::lround(horizontal_offsets[static_cast<size_t>(index)]));
    const int offset_y = static_cast<int>(
        std::lround(vertical_offsets[static_cast<size_t>(index)]));

    if (offset_x == 0 && offset_y == 0) {
      continue;
    }

    GuidedPatch& patch = (*patches)[static_cast<size_t>(index)];
    patch.center_x = positive_mod(patch.center_x + offset_x, panorama_width);
    patch.vertical_offset += offset_y;

    if (refinement_metrics != nullptr) {
      const int abs_x = std::abs(offset_x);
      const int abs_y = std::abs(offset_y);
      refinement_metrics->global_offset_applied_frame_count += 1;
      refinement_metrics->total_abs_global_offset_x += abs_x;
      refinement_metrics->total_abs_global_offset_y += abs_y;
      refinement_metrics->max_abs_global_offset_x = std::max(
          refinement_metrics->max_abs_global_offset_x,
          abs_x);
      refinement_metrics->max_abs_global_offset_y = std::max(
          refinement_metrics->max_abs_global_offset_y,
          abs_y);
    }
  }
}

float smooth_step(float edge0, float edge1, float value) {
  if (edge0 == edge1) {
    return value < edge0 ? 0.0F : 1.0F;
  }

  const float t = std::clamp((value - edge0) / (edge1 - edge0), 0.0F, 1.0F);
  return t * t * (3.0F - (2.0F * t));
}

double adaptive_seam_column_cost(
    const cv::Mat& previous_gray,
    const cv::Mat& current_gray,
    int column) {
  double cost = 0.0;
  for (int y = 0; y < previous_gray.rows; ++y) {
    const int previous_value = previous_gray.at<unsigned char>(y, column);
    const int current_value = current_gray.at<unsigned char>(y, column);
    cost += static_cast<double>(std::abs(previous_value - current_value));

    if (column > 0) {
      const int previous_gradient = std::abs(
          previous_value - previous_gray.at<unsigned char>(y, column - 1));
      const int current_gradient = std::abs(
          current_value - current_gray.at<unsigned char>(y, column - 1));
      cost += static_cast<double>(previous_gradient + current_gradient) * 0.22;
    }
  }
  return cost / static_cast<double>(std::max(1, previous_gray.rows));
}

bool choose_guided_adaptive_seam(
    const GuidedPatch& previous,
    const GuidedPatch& current,
    int panorama_width,
    int patch_width,
    int* seam_x,
    double* seam_cost) {
  if (seam_x == nullptr ||
      seam_cost == nullptr ||
      !previous.valid ||
      !current.valid ||
      previous.patch.empty() ||
      current.patch.empty()) {
    return false;
  }

  const int previous_start_x = previous.center_x - (patch_width / 2);
  const int current_start_x = current.center_x - (patch_width / 2);
  int expected_delta_x = current_start_x - previous_start_x;
  while (expected_delta_x <= 0) {
    expected_delta_x += panorama_width;
  }
  while (expected_delta_x > panorama_width / 2) {
    expected_delta_x -= panorama_width;
  }

  if (expected_delta_x <= 0 || expected_delta_x >= patch_width) {
    return false;
  }

  const int overlap_width = patch_width - expected_delta_x;
  if (overlap_width < kGuidedAdaptiveSeamMinOverlapPixels) {
    return false;
  }

  const int vertical_delta = previous.vertical_offset - current.vertical_offset;
  const int previous_top = std::max(0, -vertical_delta);
  const int current_top = previous_top + vertical_delta;
  const int common_height = std::min(
      previous.patch.rows - previous_top,
      current.patch.rows - current_top);
  if (current_top < 0 ||
      previous_top < 0 ||
      common_height < 32) {
    return false;
  }

  const cv::Rect previous_overlap(
      expected_delta_x,
      previous_top,
      overlap_width,
      common_height);
  const cv::Rect current_overlap(
      0,
      current_top,
      overlap_width,
      common_height);

  cv::Mat previous_gray;
  cv::Mat current_gray;
  cv::cvtColor(previous.patch(previous_overlap), previous_gray, cv::COLOR_BGR2GRAY);
  cv::cvtColor(current.patch(current_overlap), current_gray, cv::COLOR_BGR2GRAY);

  const int margin = std::clamp(overlap_width / 8, 4, overlap_width / 3);
  const int first_candidate = margin;
  const int last_candidate = overlap_width - margin - 1;
  if (first_candidate >= last_candidate) {
    return false;
  }

  const int smooth_radius = std::clamp(overlap_width / 20, 2, 8);
  double best_cost = std::numeric_limits<double>::max();
  int best_column = overlap_width / 2;

  for (int column = first_candidate; column <= last_candidate; ++column) {
    double window_cost = 0.0;
    int samples = 0;
    for (int offset = -smooth_radius; offset <= smooth_radius; ++offset) {
      const int sample_column =
          std::clamp(column + offset, 0, overlap_width - 1);
      window_cost += adaptive_seam_column_cost(
          previous_gray,
          current_gray,
          sample_column);
      samples += 1;
    }
    const double average_cost = window_cost / static_cast<double>(samples);
    if (average_cost < best_cost) {
      best_cost = average_cost;
      best_column = column;
    }
  }

  *seam_x = best_column;
  *seam_cost = best_cost;
  return true;
}

void apply_guided_adaptive_seam_blending(
    std::vector<GuidedPatch>* patches,
    int panorama_width,
    int patch_width,
    int base_blend_width,
    GuidedRefinementMetrics* refinement_metrics) {
  if (patches == nullptr || patches->size() < 2) {
    return;
  }

  if (refinement_metrics != nullptr) {
    refinement_metrics->adaptive_seam_blending_enabled = true;
  }

  const int patch_count = static_cast<int>(patches->size());
  for (int current_index = 0; current_index < patch_count; ++current_index) {
    const int previous_index =
        current_index == 0 ? patch_count - 1 : current_index - 1;

    int seam_x = 0;
    double seam_cost = 0.0;
    if (!choose_guided_adaptive_seam(
            (*patches)[static_cast<size_t>(previous_index)],
            (*patches)[static_cast<size_t>(current_index)],
            panorama_width,
            patch_width,
            &seam_x,
            &seam_cost)) {
      continue;
    }

    const int previous_start_x =
        (*patches)[static_cast<size_t>(previous_index)].center_x -
        (patch_width / 2);
    const int current_start_x =
        (*patches)[static_cast<size_t>(current_index)].center_x -
        (patch_width / 2);
    int expected_delta_x = current_start_x - previous_start_x;
    while (expected_delta_x <= 0) {
      expected_delta_x += panorama_width;
    }
    while (expected_delta_x > panorama_width / 2) {
      expected_delta_x -= panorama_width;
    }

    const double normalized_cost =
        std::clamp((seam_cost - 8.0) / 28.0, 0.0, 1.0);
    int seam_blend_width = std::clamp(
        static_cast<int>(std::lround(
            static_cast<double>(base_blend_width) *
            (0.90 - (normalized_cost * 0.40)))),
        12,
        std::max(12, base_blend_width));
    const bool is_closure_seam =
        current_index == 0 && previous_index > current_index;
    const GuidedRefinementPair* pair_metrics = find_guided_pair_metrics(
        refinement_metrics,
        previous_index,
        current_index);
    const int pair_risk =
        pair_metrics == nullptr ? 0 : guided_pair_risk_score(*pair_metrics);
    const bool has_refinement_support =
        pair_metrics != nullptr && pair_metrics->refined;
    const bool should_soften_weak_seam =
        pair_risk >= kGuidedWeakSeamRiskScore ||
        seam_cost >= static_cast<double>(kGuidedWeakSeamLowConfidenceCost);
    if (should_soften_weak_seam) {
      seam_blend_width = guided_softened_seam_blend_width(
          base_blend_width,
          patch_width,
          pair_risk,
          seam_cost,
          is_closure_seam,
          has_refinement_support);
    }

    GuidedPatch& previous_patch = (*patches)[static_cast<size_t>(previous_index)];
    GuidedPatch& current_patch = (*patches)[static_cast<size_t>(current_index)];
    previous_patch.right_seam_x = expected_delta_x + seam_x;
    previous_patch.right_seam_blend_width = seam_blend_width;
    current_patch.left_seam_x = seam_x;
    current_patch.left_seam_blend_width = seam_blend_width;

    if (refinement_metrics != nullptr) {
      const int overlap_width = patch_width - expected_delta_x;
      const int offset_from_center = std::abs(seam_x - (overlap_width / 2));
      refinement_metrics->adaptive_seam_count += 1;
      refinement_metrics->total_adaptive_seam_cost += seam_cost;
      refinement_metrics->max_adaptive_seam_cost = std::max(
          refinement_metrics->max_adaptive_seam_cost,
          seam_cost);
      refinement_metrics->total_abs_adaptive_seam_offset += offset_from_center;
      refinement_metrics->max_abs_adaptive_seam_offset = std::max(
          refinement_metrics->max_abs_adaptive_seam_offset,
          offset_from_center);
      if (seam_cost >= 32.0) {
        refinement_metrics->adaptive_seam_low_confidence_count += 1;
      }
      if (should_soften_weak_seam) {
        refinement_metrics->weak_seam_softening_enabled = true;
        refinement_metrics->weak_seam_softened_count += 1;
        if (is_closure_seam) {
          refinement_metrics->weak_closure_seam_softened_count += 1;
        }
        refinement_metrics->weak_seam_max_risk_score = std::max(
            refinement_metrics->weak_seam_max_risk_score,
            pair_risk);
        refinement_metrics->weak_seam_total_blend_width += seam_blend_width;
        refinement_metrics->weak_seam_max_blend_width = std::max(
            refinement_metrics->weak_seam_max_blend_width,
            seam_blend_width);
      }
    }
  }
}

float guided_patch_alpha(
    const GuidedPatch& patch,
    int x,
    int patch_width,
    int fallback_blend_width) {
  float alpha = 1.0F;

  if (patch.left_seam_x >= 0) {
    const int width = std::max(1, patch.left_seam_blend_width);
    alpha *= smooth_step(
        static_cast<float>(patch.left_seam_x - (width / 2)),
        static_cast<float>(patch.left_seam_x + (width / 2)),
        static_cast<float>(x));
  } else if (x < fallback_blend_width) {
    alpha *= static_cast<float>(x + 1) /
        static_cast<float>(fallback_blend_width + 1);
  }

  if (patch.right_seam_x >= 0) {
    const int width = std::max(1, patch.right_seam_blend_width);
    alpha *= 1.0F - smooth_step(
        static_cast<float>(patch.right_seam_x - (width / 2)),
        static_cast<float>(patch.right_seam_x + (width / 2)),
        static_cast<float>(x));
  } else if (x >= patch_width - fallback_blend_width) {
    alpha *= static_cast<float>(patch_width - x) /
        static_cast<float>(fallback_blend_width + 1);
  }

  return std::clamp(alpha, 0.0F, 1.0F);
}

int guided_pair_cut_x(
    const std::vector<GuidedPatch>& patches,
    const GuidedRefinementPair& pair,
    int panorama_width,
    int patch_width) {
  if (patches.empty() ||
      pair.from_index < 0 ||
      pair.to_index < 0 ||
      pair.from_index >= static_cast<int>(patches.size()) ||
      pair.to_index >= static_cast<int>(patches.size())) {
    return 0;
  }

  const GuidedPatch& previous = patches[static_cast<size_t>(pair.from_index)];
  const GuidedPatch& current = patches[static_cast<size_t>(pair.to_index)];
  const int previous_start_x = previous.center_x - (patch_width / 2);
  const int current_start_x = current.center_x - (patch_width / 2);
  int expected_delta_x = current_start_x - previous_start_x;
  while (expected_delta_x <= 0) {
    expected_delta_x += panorama_width;
  }
  while (expected_delta_x > panorama_width / 2) {
    expected_delta_x -= panorama_width;
  }

  const int overlap_width = std::max(1, patch_width - expected_delta_x);
  return positive_mod(current_start_x + (overlap_width / 2), panorama_width);
}

const GuidedRefinementPair* select_guided_output_cut_pair(
    GuidedRefinementMetrics* refinement_metrics,
    int* best_risk,
    int* closure_risk) {
  if (refinement_metrics == nullptr ||
      refinement_metrics->pairs.empty() ||
      best_risk == nullptr ||
      closure_risk == nullptr) {
    return nullptr;
  }

  const GuidedRefinementPair* best_pair = nullptr;
  *best_risk = 101;
  *closure_risk = 0;
  for (const GuidedRefinementPair& pair : refinement_metrics->pairs) {
    const int risk = guided_pair_risk_score(pair);
    if (pair.to_index == 0 && pair.from_index > pair.to_index) {
      *closure_risk = risk;
    }
    if (risk < *best_risk) {
      *best_risk = risk;
      best_pair = &pair;
    }
  }
  return best_pair;
}

void roll_panorama_horizontally(cv::Mat* output, int roll_pixels) {
  if (output == nullptr || output->empty() || output->cols <= 1) {
    return;
  }

  const int normalized_roll = positive_mod(roll_pixels, output->cols);
  if (normalized_roll == 0) {
    return;
  }

  cv::Mat rolled(output->rows, output->cols, output->type());
  const int right_width = output->cols - normalized_roll;
  output->colRange(normalized_roll, output->cols)
      .copyTo(rolled.colRange(0, right_width));
  output->colRange(0, normalized_roll)
      .copyTo(rolled.colRange(right_width, output->cols));
  *output = rolled;
}

void analyze_guided_patch_alignment(
    const std::vector<GuidedPatch>& patches,
    int panorama_width,
    int patch_width,
    GuidedRefinementMetrics* refinement_metrics) {
  if (refinement_metrics == nullptr || patches.size() < 2) {
    return;
  }

  refinement_metrics->pair_count = static_cast<int>(patches.size());
  refinement_metrics->pairs.reserve(patches.size());

  for (size_t i = 1; i < patches.size(); ++i) {
    GuidedRefinementPair pair_metrics;
    pair_metrics.from_index = static_cast<int>(i - 1);
    pair_metrics.to_index = static_cast<int>(i);

    if (estimate_overlap_refinement(
            patches[i - 1],
            patches[i],
            panorama_width,
            patch_width,
            false,
            false,
            false,
            &pair_metrics)) {
      record_refined_pair_metrics(pair_metrics, refinement_metrics);
    } else {
      record_rejected_pair_metrics(pair_metrics, refinement_metrics);
    }
  }

  GuidedRefinementPair closing_pair_metrics;
  closing_pair_metrics.from_index = static_cast<int>(patches.size() - 1);
  closing_pair_metrics.to_index = 0;
  if (estimate_overlap_refinement(
          patches.back(),
          patches.front(),
          panorama_width,
          patch_width,
          false,
          false,
          true,
          &closing_pair_metrics)) {
    record_refined_pair_metrics(closing_pair_metrics, refinement_metrics);
  } else {
    record_rejected_pair_metrics(closing_pair_metrics, refinement_metrics);
  }
}

void choose_and_apply_guided_output_cut(
    cv::Mat* output,
    const std::vector<GuidedPatch>& patches,
    int panorama_width,
    int patch_width,
    GuidedRefinementMetrics* refinement_metrics) {
  if (output == nullptr ||
      output->empty() ||
      refinement_metrics == nullptr ||
      refinement_metrics->pairs.empty()) {
    return;
  }

  int best_risk = 0;
  int closure_risk = 0;
  const GuidedRefinementPair* best_pair = select_guided_output_cut_pair(
      refinement_metrics,
      &best_risk,
      &closure_risk);

  refinement_metrics->closure_seam_risk_score = closure_risk;
  if (best_pair == nullptr) {
    return;
  }

  const int cut_x = guided_pair_cut_x(
      patches,
      *best_pair,
      panorama_width,
      patch_width);
  refinement_metrics->output_roll_pixels = cut_x;
  refinement_metrics->output_cut_seam =
      std::to_string(best_pair->from_index) + "->" +
      std::to_string(best_pair->to_index);
  refinement_metrics->output_cut_seam_risk_score = best_risk;

  if (closure_risk >= 30 && best_risk + 12 < closure_risk) {
    roll_panorama_horizontally(output, cut_x);
  } else {
    refinement_metrics->output_roll_pixels = 0;
    refinement_metrics->output_cut_seam = "closure";
    refinement_metrics->output_cut_seam_risk_score = closure_risk;
  }
}

int guided_forward_patch_delta(
    const GuidedPatch& previous,
    const GuidedPatch& current,
    int panorama_width) {
  int delta = current.center_x - previous.center_x;
  while (delta <= 0) {
    delta += panorama_width;
  }
  while (delta > panorama_width / 2) {
    delta -= panorama_width;
  }
  return delta;
}

#if GIRO360_EXPERIMENT_LOCAL_MESH_WARP
bool is_guided_local_mesh_warp_candidate(
    const GuidedRefinementPair* pair) {
  return pair != nullptr &&
      pair->refined &&
      pair->filtered_matches >= 12 &&
      pair->spatial_bin_count <= 2 &&
      std::abs(pair->correction_y) >= 12;
}

bool build_guided_consistent_mesh_flow(
    const cv::Mat& previous_gray,
    const cv::Mat& current_gray,
    cv::Mat* mesh_flow,
    double* reliable_ratio,
    double* max_displacement) {
  if (mesh_flow == nullptr ||
      reliable_ratio == nullptr ||
      max_displacement == nullptr ||
      previous_gray.empty() ||
      current_gray.empty() ||
      previous_gray.size() != current_gray.size()) {
    return false;
  }

  cv::Mat forward_flow;
  cv::Mat reverse_flow;
  cv::calcOpticalFlowFarneback(
      previous_gray,
      current_gray,
      forward_flow,
      0.5,
      4,
      21,
      5,
      7,
      1.5,
      cv::OPTFLOW_FARNEBACK_GAUSSIAN);
  cv::calcOpticalFlowFarneback(
      current_gray,
      previous_gray,
      reverse_flow,
      0.5,
      4,
      21,
      5,
      7,
      1.5,
      cv::OPTFLOW_FARNEBACK_GAUSSIAN);

  if (forward_flow.empty() || reverse_flow.empty()) {
    return false;
  }

  cv::Mat gradient_x;
  cv::Mat gradient_y;
  cv::Sobel(previous_gray, gradient_x, CV_32F, 1, 0, 3);
  cv::Sobel(previous_gray, gradient_y, CV_32F, 0, 1, 3);

  cv::Mat reverse_map_x(previous_gray.size(), CV_32F);
  cv::Mat reverse_map_y(previous_gray.size(), CV_32F);
  for (int y = 0; y < previous_gray.rows; ++y) {
    for (int x = 0; x < previous_gray.cols; ++x) {
      const cv::Vec2f flow = forward_flow.at<cv::Vec2f>(y, x);
      reverse_map_x.at<float>(y, x) = static_cast<float>(x) + flow[0];
      reverse_map_y.at<float>(y, x) = static_cast<float>(y) + flow[1];
    }
  }

  cv::Mat reverse_at_forward;
  cv::remap(
      reverse_flow,
      reverse_at_forward,
      reverse_map_x,
      reverse_map_y,
      cv::INTER_LINEAR,
      cv::BORDER_CONSTANT,
      cv::Scalar::all(0));

  cv::Mat weighted_flow(
      previous_gray.size(),
      CV_32FC2,
      cv::Scalar::all(0));
  cv::Mat confidence(
      previous_gray.size(),
      CV_32F,
      cv::Scalar::all(0));
  int reliable_pixels = 0;
  const int total_pixels = previous_gray.rows * previous_gray.cols;

  for (int y = 0; y < previous_gray.rows; ++y) {
    for (int x = 0; x < previous_gray.cols; ++x) {
      const cv::Vec2f forward = forward_flow.at<cv::Vec2f>(y, x);
      const float mapped_x = static_cast<float>(x) + forward[0];
      const float mapped_y = static_cast<float>(y) + forward[1];
      if (mapped_x < 1.0F ||
          mapped_y < 1.0F ||
          mapped_x >= static_cast<float>(previous_gray.cols - 1) ||
          mapped_y >= static_cast<float>(previous_gray.rows - 1)) {
        continue;
      }

      const cv::Vec2f reverse = reverse_at_forward.at<cv::Vec2f>(y, x);
      const cv::Vec2f consistency = forward + reverse;
      const double consistency_error = cv::norm(consistency);
      const double gradient =
          std::abs(gradient_x.at<float>(y, x)) +
          std::abs(gradient_y.at<float>(y, x));
      if (consistency_error > kGuidedMeshWarpMaxConsistencyError ||
          gradient < 8.0 ||
          std::abs(forward[0]) > kGuidedMeshWarpMaxHorizontalPixels * 1.5 ||
          std::abs(forward[1]) > kGuidedMeshWarpMaxVerticalPixels * 1.5) {
        continue;
      }

      weighted_flow.at<cv::Vec2f>(y, x) = forward;
      confidence.at<float>(y, x) = 1.0F;
      reliable_pixels += 1;
    }
  }

  *reliable_ratio = total_pixels == 0
      ? 0.0
      : static_cast<double>(reliable_pixels) /
          static_cast<double>(total_pixels);
  if (*reliable_ratio < kGuidedMeshWarpMinReliableRatio) {
    return false;
  }

  const cv::Size grid_size(
      kGuidedMeshWarpGridColumns,
      kGuidedMeshWarpGridRows);
  cv::Mat grid_flow;
  cv::Mat grid_confidence;
  cv::resize(weighted_flow, grid_flow, grid_size, 0, 0, cv::INTER_AREA);
  cv::resize(confidence, grid_confidence, grid_size, 0, 0, cv::INTER_AREA);

  *max_displacement = 0.0;
  for (int y = 0; y < grid_flow.rows; ++y) {
    for (int x = 0; x < grid_flow.cols; ++x) {
      const float weight = grid_confidence.at<float>(y, x);
      cv::Vec2f& flow = grid_flow.at<cv::Vec2f>(y, x);
      if (weight <= 0.01F) {
        flow = cv::Vec2f(0.0F, 0.0F);
        continue;
      }
      flow /= weight;
      flow[0] = std::clamp(
          flow[0],
          -static_cast<float>(kGuidedMeshWarpMaxHorizontalPixels),
          static_cast<float>(kGuidedMeshWarpMaxHorizontalPixels));
      flow[1] = std::clamp(
          flow[1],
          -static_cast<float>(kGuidedMeshWarpMaxVerticalPixels),
          static_cast<float>(kGuidedMeshWarpMaxVerticalPixels));
      *max_displacement = std::max(
          *max_displacement,
          cv::norm(flow));
    }
  }

  cv::GaussianBlur(grid_flow, grid_flow, cv::Size(3, 3), 0.0);
  cv::resize(
      grid_flow,
      *mesh_flow,
      previous_gray.size(),
      0,
      0,
      cv::INTER_CUBIC);
  return !mesh_flow->empty();
}

bool apply_guided_local_mesh_warp_to_pair(
    const GuidedPatch& previous,
    GuidedPatch* current,
    int panorama_width,
    int patch_width,
    double* reliable_ratio,
    double* max_displacement) {
  if (current == nullptr ||
      reliable_ratio == nullptr ||
      max_displacement == nullptr ||
      !previous.valid ||
      !current->valid ||
      previous.patch.empty() ||
      current->patch.empty()) {
    return false;
  }

  const int expected_delta_x = guided_forward_patch_delta(
      previous,
      *current,
      panorama_width);
  if (expected_delta_x <= 0 || expected_delta_x >= patch_width) {
    return false;
  }

  const int overlap_width = patch_width - expected_delta_x;
  if (overlap_width < kGuidedAdaptiveSeamMinOverlapPixels) {
    return false;
  }

  const int vertical_delta = previous.vertical_offset - current->vertical_offset;
  const int previous_top = std::max(0, -vertical_delta);
  const int current_top = previous_top + vertical_delta;
  const int common_height = std::min(
      previous.patch.rows - previous_top,
      current->patch.rows - current_top);
  if (previous_top < 0 || current_top < 0 || common_height < 64) {
    return false;
  }

  const cv::Rect previous_overlap(
      expected_delta_x,
      previous_top,
      overlap_width,
      common_height);
  const cv::Rect current_overlap(
      0,
      current_top,
      overlap_width,
      common_height);
  cv::Mat previous_gray;
  cv::Mat current_gray;
  cv::cvtColor(
      previous.patch(previous_overlap),
      previous_gray,
      cv::COLOR_BGR2GRAY);
  cv::cvtColor(
      current->patch(current_overlap),
      current_gray,
      cv::COLOR_BGR2GRAY);

  cv::Mat mesh_flow;
  if (!build_guided_consistent_mesh_flow(
          previous_gray,
          current_gray,
          &mesh_flow,
          reliable_ratio,
          max_displacement)) {
    return false;
  }

  cv::Mat map_x(current->patch.size(), CV_32F);
  cv::Mat map_y(current->patch.size(), CV_32F);
  for (int y = 0; y < current->patch.rows; ++y) {
    for (int x = 0; x < current->patch.cols; ++x) {
      map_x.at<float>(y, x) = static_cast<float>(x);
      map_y.at<float>(y, x) = static_cast<float>(y);
    }
  }

  const float fade_start = static_cast<float>(overlap_width) * 0.55F;
  const float fade_end = static_cast<float>(overlap_width - 1);
  for (int y = 0; y < common_height; ++y) {
    const int patch_y = current_top + y;
    for (int x = 0; x < overlap_width; ++x) {
      const float influence = 1.0F - smooth_step(
          fade_start,
          fade_end,
          static_cast<float>(x));
      const cv::Vec2f flow = mesh_flow.at<cv::Vec2f>(y, x) * influence;
      map_x.at<float>(patch_y, x) = static_cast<float>(x) + flow[0];
      map_y.at<float>(patch_y, x) = static_cast<float>(patch_y) + flow[1];
    }
  }

  cv::Mat warped;
  cv::remap(
      current->patch,
      warped,
      map_x,
      map_y,
      cv::INTER_CUBIC,
      cv::BORDER_REFLECT101);
  if (warped.empty()) {
    return false;
  }
  current->patch = warped;
  return true;
}

void apply_guided_local_mesh_warp(
    std::vector<GuidedPatch>* patches,
    int panorama_width,
    int patch_width,
    GuidedRefinementMetrics* refinement_metrics) {
  if (patches == nullptr ||
      patches->size() < 2 ||
      refinement_metrics == nullptr) {
    return;
  }

  refinement_metrics->local_mesh_warp_enabled = true;
  for (size_t current_index = 1;
       current_index < patches->size();
       ++current_index) {
    const size_t previous_index = current_index - 1;
    const GuidedRefinementPair* pair = find_guided_pair_metrics(
        refinement_metrics,
        static_cast<int>(previous_index),
        static_cast<int>(current_index));
    if (!is_guided_local_mesh_warp_candidate(pair)) {
      continue;
    }

    refinement_metrics->local_mesh_warp_candidate_count += 1;
    double reliable_ratio = 0.0;
    double max_displacement = 0.0;
    const bool applied = apply_guided_local_mesh_warp_to_pair(
        (*patches)[previous_index],
        &(*patches)[current_index],
        panorama_width,
        patch_width,
        &reliable_ratio,
        &max_displacement);
    refinement_metrics->total_local_mesh_warp_reliable_ratio +=
        reliable_ratio;
    refinement_metrics->max_local_mesh_warp_displacement = std::max(
        refinement_metrics->max_local_mesh_warp_displacement,
        max_displacement);
    if (applied) {
      refinement_metrics->local_mesh_warp_applied_count += 1;
    } else {
      refinement_metrics->local_mesh_warp_rejected_count += 1;
    }
  }
}
#endif

void apply_guided_straight_seam_masks(
    const GuidedPatch& previous,
    const GuidedPatch& current,
    cv::UMat* previous_mask,
    cv::UMat* current_mask) {
  if (previous_mask == nullptr ||
      current_mask == nullptr ||
      previous_mask->empty() ||
      current_mask->empty()) {
    return;
  }

  if (previous.right_seam_x >= 0 &&
      previous.right_seam_x < previous_mask->cols) {
    previous_mask->colRange(previous.right_seam_x, previous_mask->cols)
        .setTo(cv::Scalar::all(0));
  }
  if (current.left_seam_x > 0 &&
      current.left_seam_x <= current_mask->cols) {
    current_mask->colRange(0, current.left_seam_x)
        .setTo(cv::Scalar::all(0));
  }
}

cv::Mat build_guided_graph_cut_multiband_output(
    const std::vector<GuidedPatch>& patches,
    int panorama_width,
    int panorama_height,
    int band_top,
    int patch_width,
    GuidedRefinementMetrics* refinement_metrics) {
  if (patches.size() < 2 ||
      refinement_metrics == nullptr ||
      refinement_metrics->pairs.empty()) {
    return cv::Mat();
  }

  int best_risk = 0;
  int closure_risk = 0;
  const GuidedRefinementPair* best_pair = select_guided_output_cut_pair(
      refinement_metrics,
      &best_risk,
      &closure_risk);
  if (best_pair == nullptr) {
    return cv::Mat();
  }

  const bool move_cut =
      closure_risk >= 30 && best_risk + 12 < closure_risk;
  const int start_index = move_cut ? best_pair->to_index : 0;
  const int cut_from_index =
      start_index == 0 ? static_cast<int>(patches.size()) - 1 : start_index - 1;
  const GuidedRefinementPair* cut_pair = find_guided_pair_metrics(
      refinement_metrics,
      cut_from_index,
      start_index);
  if (cut_pair == nullptr) {
    return cv::Mat();
  }

  const int cut_risk = guided_pair_risk_score(*cut_pair);
  refinement_metrics->closure_seam_risk_score = closure_risk;
  refinement_metrics->output_cut_seam =
      std::to_string(cut_pair->from_index) + "->" +
      std::to_string(cut_pair->to_index);
  refinement_metrics->output_cut_seam_risk_score = cut_risk;
  refinement_metrics->output_roll_pixels = move_cut
      ? guided_pair_cut_x(
          patches,
          *cut_pair,
          panorama_width,
          patch_width)
      : 0;

  std::vector<int> order;
  order.reserve(patches.size());
  for (size_t offset = 0; offset < patches.size(); ++offset) {
    order.push_back(
        (start_index + static_cast<int>(offset)) %
        static_cast<int>(patches.size()));
  }

  const int cut_delta = guided_forward_patch_delta(
      patches[static_cast<size_t>(cut_from_index)],
      patches[static_cast<size_t>(start_index)],
      panorama_width);
  if (cut_delta <= 0 || cut_delta >= patch_width) {
    return cv::Mat();
  }

  int unwrapped_center = cut_delta / 2;
  std::vector<cv::Point> corners(order.size());
  int min_x = unwrapped_center - (patch_width / 2);
  int max_x = min_x + patch_width;

  for (size_t position = 0; position < order.size(); ++position) {
    if (position > 0) {
      const GuidedPatch& previous =
          patches[static_cast<size_t>(order[position - 1])];
      const GuidedPatch& current =
          patches[static_cast<size_t>(order[position])];
      const int delta = guided_forward_patch_delta(
          previous,
          current,
          panorama_width);
      if (delta <= 0 || delta >= patch_width) {
        return cv::Mat();
      }
      unwrapped_center += delta;
    }

    const GuidedPatch& patch =
        patches[static_cast<size_t>(order[position])];
    const int corner_x = unwrapped_center - (patch_width / 2);
    corners[position] = cv::Point(
        corner_x,
        band_top + patch.vertical_offset);
    min_x = std::min(min_x, corner_x);
    max_x = std::max(max_x, corner_x + patch_width);
  }

  const int shift_x = std::max(0, -min_x);
  for (cv::Point& corner : corners) {
    corner.x += shift_x;
  }
  const int crop_x = shift_x;
  const int canvas_width = std::max(
      max_x + shift_x,
      crop_x + panorama_width);
  if (canvas_width <= 0 || crop_x + panorama_width > canvas_width) {
    return cv::Mat();
  }

  std::vector<cv::UMat> images_float(order.size());
  std::vector<cv::UMat> masks(order.size());
  for (size_t position = 0; position < order.size(); ++position) {
    const GuidedPatch& patch =
        patches[static_cast<size_t>(order[position])];
    patch.patch.convertTo(images_float[position], CV_32FC3);
    masks[position] = cv::UMat(
        patch.patch.rows,
        patch.patch.cols,
        CV_8UC1,
        cv::Scalar::all(255));
  }

  refinement_metrics->graph_cut_seam_enabled = true;
  for (size_t position = 1; position < order.size(); ++position) {
    const int previous_index = order[position - 1];
    const int current_index = order[position];
    const GuidedPatch& previous =
        patches[static_cast<size_t>(previous_index)];
    const GuidedPatch& current =
        patches[static_cast<size_t>(current_index)];
    const GuidedRefinementPair* pair = find_guided_pair_metrics(
        refinement_metrics,
        previous_index,
        current_index);
    const int pair_risk = pair == nullptr ? 0 : guided_pair_risk_score(*pair);

    if (pair_risk < kGuidedGraphCutRiskScore) {
      apply_guided_straight_seam_masks(
          previous,
          current,
          &masks[position - 1],
          &masks[position]);
      continue;
    }

    try {
      std::vector<cv::UMat> pair_images = {
          images_float[position - 1],
          images_float[position],
      };
      std::vector<cv::Point> pair_corners = {
          corners[position - 1],
          corners[position],
      };
      std::vector<cv::UMat> pair_masks = {
          cv::UMat(
              previous.patch.rows,
              previous.patch.cols,
              CV_8UC1,
              cv::Scalar::all(255)),
          cv::UMat(
              current.patch.rows,
              current.patch.cols,
              CV_8UC1,
              cv::Scalar::all(255)),
      };
      cv::detail::GraphCutSeamFinder seam_finder(
          cv::detail::GraphCutSeamFinderBase::COST_COLOR_GRAD);
      seam_finder.find(pair_images, pair_corners, pair_masks);
      cv::bitwise_and(
          masks[position - 1],
          pair_masks[0],
          masks[position - 1]);
      cv::bitwise_and(
          masks[position],
          pair_masks[1],
          masks[position]);
      refinement_metrics->graph_cut_seam_count += 1;
    } catch (const cv::Exception&) {
      refinement_metrics->graph_cut_seam_failed_count += 1;
      apply_guided_straight_seam_masks(
          previous,
          current,
          &masks[position - 1],
          &masks[position]);
    }
  }

  cv::detail::MultiBandBlender blender(
      false,
      kGuidedMultiBandCount,
      CV_32F);
  blender.prepare(cv::Rect(0, 0, canvas_width, panorama_height));
  for (size_t position = 0; position < order.size(); ++position) {
    const GuidedPatch& patch =
        patches[static_cast<size_t>(order[position])];
    cv::Mat image_16s;
    patch.patch.convertTo(image_16s, CV_16SC3);
    blender.feed(image_16s, masks[position], corners[position]);
  }

  cv::Mat blended;
  cv::Mat blended_mask;
  blender.blend(blended, blended_mask);
  if (blended.empty() || blended.cols < crop_x + panorama_width) {
    return cv::Mat();
  }

  cv::Mat output;
  blended(
      cv::Rect(crop_x, 0, panorama_width, panorama_height))
      .convertTo(output, CV_8UC3);
  refinement_metrics->multiband_blending_enabled = true;
  refinement_metrics->multiband_count = kGuidedMultiBandCount;
  return output;
}

void refine_guided_patch_alignment(
    std::vector<GuidedPatch>* patches,
    int panorama_width,
    int patch_width,
    bool trust_vertical_telemetry,
    bool relax_vertical_corrections,
    GuidedRefinementMetrics* refinement_metrics,
    const std::vector<GuidedRefinementPair>* affine_pairs = nullptr) {
  if (patches == nullptr || patches->size() < 2) {
    return;
  }

  if (refinement_metrics != nullptr) {
    refinement_metrics->pair_count = static_cast<int>(patches->size());
    refinement_metrics->pairs.reserve(patches->size());
  }

  std::vector<GuidedRefinementPair> measured_pairs;
  measured_pairs.reserve(patches->size());

  for (size_t i = 1; i < patches->size(); ++i) {
    GuidedRefinementPair pair_metrics;
    pair_metrics.from_index = static_cast<int>(i - 1);
    pair_metrics.to_index = static_cast<int>(i);

    const bool refined = estimate_overlap_refinement(
            (*patches)[i - 1],
            (*patches)[i],
            panorama_width,
            patch_width,
            trust_vertical_telemetry,
            relax_vertical_corrections,
            false,
            &pair_metrics);
    copy_guided_affine_diagnostics(affine_pairs, &pair_metrics);
    if (!refined) {
      record_rejected_pair_metrics(pair_metrics, refinement_metrics);
      measured_pairs.push_back(pair_metrics);
      continue;
    }

    record_refined_pair_metrics(pair_metrics, refinement_metrics);
    measured_pairs.push_back(pair_metrics);
  }

  GuidedRefinementPair closing_pair_metrics;
  closing_pair_metrics.from_index = static_cast<int>(patches->size() - 1);
  closing_pair_metrics.to_index = 0;

  const bool closing_refined = estimate_overlap_refinement(
          patches->back(),
          patches->front(),
          panorama_width,
          patch_width,
          trust_vertical_telemetry,
          relax_vertical_corrections,
          true,
          &closing_pair_metrics);
  copy_guided_affine_diagnostics(affine_pairs, &closing_pair_metrics);
  if (!closing_refined) {
    record_rejected_pair_metrics(closing_pair_metrics, refinement_metrics);
    measured_pairs.push_back(closing_pair_metrics);
  } else {
    record_refined_pair_metrics(closing_pair_metrics, refinement_metrics);
    measured_pairs.push_back(closing_pair_metrics);
  }

  apply_guided_global_offset_optimization(
      patches,
      measured_pairs,
      panorama_width,
      trust_vertical_telemetry,
      refinement_metrics);
}

cv::Mat build_guided_cylindrical_fallback(
    const std::vector<cv::Mat>& images,
    const double* actual_yaws,
    const double* actual_pitches,
    int guided_fill_mode,
    int guided_alignment_mode,
    GuidedRefinementMetrics* refinement_metrics) {
  const int image_count = static_cast<int>(images.size());
  const bool has_telemetry = actual_yaws != nullptr && actual_pitches != nullptr;
  const int base_segment_width = 260;
  const int calibrated_image_count = 20;
  const int calibrated_panorama_width =
      calibrated_image_count * base_segment_width;
  const int requested_panorama_width = image_count >= calibrated_image_count
      ? calibrated_panorama_width
      : image_count * base_segment_width;
  int panorama_width =
      std::clamp(requested_panorama_width, 2048, 6144);
  panorama_width = std::max(image_count, (panorama_width / image_count) * image_count);

  const int panorama_height = std::max(1, panorama_width / 2);
  const int segment_width = std::max(1, panorama_width / image_count);
  const int calibrated_patch_width = GIRO360_EXPERIMENT_EXPANDED_OVERLAP
      ? 520
      : 390;
  const int requested_patch_width = image_count >= calibrated_image_count
      ? static_cast<int>(std::lround(
            static_cast<double>(calibrated_patch_width) *
            static_cast<double>(panorama_width) /
            static_cast<double>(calibrated_panorama_width)))
      : segment_width +
          (2 * (GIRO360_EXPERIMENT_EXPANDED_OVERLAP
              ? std::max(8, segment_width / 2)
              : std::max(8, segment_width / 4)));
  const int blend_width = std::max(
      8,
      (std::max(segment_width + 16, requested_patch_width) - segment_width) /
          2);
  const int patch_width = segment_width + (blend_width * 2);
  const double source_crop_ratio =
      GIRO360_EXPERIMENT_EXPANDED_OVERLAP ? 0.62 : 0.46;
  const int band_height = std::max(1, panorama_height / 2);
  const int band_top = (panorama_height - band_height) / 2;

  std::vector<GuidedPatch> guided_patches(static_cast<size_t>(image_count));
  cv::Mat accumulator(panorama_height, panorama_width, CV_32FC3, cv::Scalar::all(0));
  cv::Mat weights(panorama_height, panorama_width, CV_32FC1, cv::Scalar::all(0));
  double max_abs_pitch_degrees = 0.0;
  const bool trust_vertical_telemetry = has_telemetry &&
      should_trust_vertical_telemetry(
          actual_pitches,
          image_count,
          &max_abs_pitch_degrees);

  if (refinement_metrics != nullptr) {
    refinement_metrics->vertical_telemetry_trusted = trust_vertical_telemetry;
    refinement_metrics->vertical_telemetry_max_abs_pitch_degrees =
        max_abs_pitch_degrees;
    refinement_metrics->alignment_mode =
        guided_alignment_mode_name(guided_alignment_mode);
    refinement_metrics->alignment_refine_disabled =
        guided_alignment_mode == kGuidedAlignmentTelemetryOnly;
    refinement_metrics->expanded_overlap_enabled =
        GIRO360_EXPERIMENT_EXPANDED_OVERLAP;
    refinement_metrics->expanded_overlap_source_crop_percent =
        static_cast<int>(std::lround(source_crop_ratio * 100.0));
    refinement_metrics->expanded_overlap_patch_width = patch_width;
    refinement_metrics->expanded_overlap_width = patch_width - segment_width;
  }

  for (int image_index = 0; image_index < image_count; ++image_index) {
    const cv::Mat& image = images[static_cast<size_t>(image_index)];
    if (image.empty()) {
      continue;
    }

    const int crop_width = std::max(
        1,
        static_cast<int>(std::lround(image.cols * source_crop_ratio)));
    const int crop_x = std::max(0, (image.cols - crop_width) / 2);
    const cv::Rect crop_rect(crop_x, 0, std::min(crop_width, image.cols - crop_x), image.rows);

    cv::Mat patch;
    cv::resize(image(crop_rect), patch, cv::Size(patch_width, band_height), 0, 0, cv::INTER_AREA);

    int center_x = (image_index * segment_width) + (segment_width / 2);
    int vertical_offset = 0;

    if (has_telemetry &&
        std::isfinite(actual_yaws[image_index]) &&
        std::isfinite(actual_pitches[image_index])) {
      const double yaw = normalize_positive_angle(actual_yaws[image_index]);
      center_x = static_cast<int>(std::lround((yaw / kTwoPi) * panorama_width)) +
          (segment_width / 2);
      center_x %= panorama_width;

      const double pitch = std::clamp(actual_pitches[image_index], -kPi / 2.0, kPi / 2.0);
      vertical_offset = static_cast<int>(std::lround((-pitch / kPi) * panorama_height));
    }

    guided_patches[static_cast<size_t>(image_index)] = GuidedPatch{
        patch,
        center_x,
        vertical_offset,
        true,
    };
  }

  if (guided_alignment_mode == kGuidedAlignmentTelemetryOnly) {
    analyze_guided_patch_alignment(
        guided_patches,
        panorama_width,
        patch_width,
        refinement_metrics);
  } else {
    std::vector<GuidedRefinementPair> affine_pairs;
    if (guided_alignment_mode == kGuidedAlignmentAffineLocalRefine) {
      affine_pairs = apply_guided_affine_pose_refinement(
          &guided_patches,
          panorama_width,
          patch_width,
          refinement_metrics);
    }
    const bool guard_vertical_corrections =
        trust_vertical_telemetry ||
        guided_alignment_mode == kGuidedAlignmentHorizontalRefine ||
        guided_alignment_mode == kGuidedAlignmentVideoRefine;
    const bool relax_video_vertical_corrections =
        guided_alignment_mode == kGuidedAlignmentVideoRefine;
    refine_guided_patch_alignment(
        &guided_patches,
        panorama_width,
        patch_width,
        guard_vertical_corrections,
        relax_video_vertical_corrections,
        refinement_metrics,
        affine_pairs.empty() ? nullptr : &affine_pairs);
    smooth_guided_vertical_offsets(&guided_patches, refinement_metrics);
  }

#if GIRO360_EXPERIMENT_LOCAL_MESH_WARP
  apply_guided_local_mesh_warp(
      &guided_patches,
      panorama_width,
      patch_width,
      refinement_metrics);
#endif

  apply_guided_exposure_compensation(&guided_patches, refinement_metrics);

  apply_guided_adaptive_seam_blending(
      &guided_patches,
      panorama_width,
      patch_width,
      blend_width,
      refinement_metrics);

#if GIRO360_EXPERIMENT_GRAPH_CUT_MULTIBAND
  cv::Mat graph_cut_output = build_guided_graph_cut_multiband_output(
      guided_patches,
      panorama_width,
      panorama_height,
      band_top,
      patch_width,
      refinement_metrics);
  if (!graph_cut_output.empty()) {
    return graph_cut_output;
  }
#endif

  for (const GuidedPatch& guided_patch : guided_patches) {
    if (!guided_patch.valid || guided_patch.patch.empty()) {
      continue;
    }

    cv::Mat patch;
    guided_patch.patch.convertTo(patch, CV_32FC3);
    const int start_x = guided_patch.center_x - (patch_width / 2);

    for (int y = 0; y < patch.rows; ++y) {
      const int output_y = band_top + y + guided_patch.vertical_offset;
      if (output_y < 0 || output_y >= panorama_height) {
        continue;
      }
      for (int x = 0; x < patch.cols; ++x) {
        const float alpha = guided_patch_alpha(
            guided_patch,
            x,
            patch_width,
            blend_width);
        if (alpha <= kWeightEpsilon) {
          continue;
        }

        int output_x = (start_x + x) % panorama_width;
        if (output_x < 0) {
          output_x += panorama_width;
        }

        accumulator.at<cv::Vec3f>(output_y, output_x) +=
            patch.at<cv::Vec3f>(y, x) * alpha;
        weights.at<float>(output_y, output_x) += alpha;
      }
    }
  }

  cv::Mat output(panorama_height, panorama_width, CV_8UC3, cv::Scalar::all(0));
  for (int y = 0; y < panorama_height; ++y) {
    for (int x = 0; x < panorama_width; ++x) {
      const float weight = weights.at<float>(y, x);
      if (weight <= kWeightEpsilon) {
        continue;
      }

      const cv::Vec3f color = accumulator.at<cv::Vec3f>(y, x) / weight;
      output.at<cv::Vec3b>(y, x) = cv::Vec3b(
          cv::saturate_cast<unsigned char>(color[0]),
          cv::saturate_cast<unsigned char>(color[1]),
          cv::saturate_cast<unsigned char>(color[2]));
    }
  }

  if (guided_fill_mode == kGuidedFillEdge) {
    fill_uncovered_vertical_edges(&output, weights);
  }
  choose_and_apply_guided_output_cut(
      &output,
      guided_patches,
      panorama_width,
      patch_width,
      refinement_metrics);
  return output;
}

void fill_uncovered_vertical_edges(cv::Mat* output, const cv::Mat& weights) {
  if (output == nullptr || output->empty() || weights.empty()) {
    return;
  }

  for (int x = 0; x < output->cols; ++x) {
    int first_covered_y = -1;
    int last_covered_y = -1;

    for (int y = 0; y < output->rows; ++y) {
      if (weights.at<float>(y, x) > kWeightEpsilon) {
        first_covered_y = y;
        break;
      }
    }
    for (int y = output->rows - 1; y >= 0; --y) {
      if (weights.at<float>(y, x) > kWeightEpsilon) {
        last_covered_y = y;
        break;
      }
    }

    if (first_covered_y < 0 || last_covered_y < 0) {
      continue;
    }

    const cv::Vec3b top_color = output->at<cv::Vec3b>(first_covered_y, x);
    const cv::Vec3b bottom_color = output->at<cv::Vec3b>(last_covered_y, x);

    for (int y = 0; y < first_covered_y; ++y) {
      output->at<cv::Vec3b>(y, x) = top_color;
    }
    for (int y = last_covered_y + 1; y < output->rows; ++y) {
      output->at<cv::Vec3b>(y, x) = bottom_color;
    }
  }
}

cv::Mat force_equirectangular_ratio(const cv::Mat& panorama) {
  const int target_height = std::max(1, panorama.cols / 2);

  if (target_height == panorama.rows) {
    return panorama;
  }

  if (target_height < panorama.rows) {
    const int y = std::max(0, (panorama.rows - target_height) / 2);
    return panorama(cv::Rect(0, y, panorama.cols, target_height)).clone();
  }

  const int top = (target_height - panorama.rows) / 2;
  const int bottom = target_height - panorama.rows - top;
  cv::Mat padded;
  cv::copyMakeBorder(
      panorama,
      padded,
      top,
      bottom,
      0,
      0,
      cv::BORDER_CONSTANT,
      cv::Scalar::all(0));
  return padded;
}

}  // namespace

#if defined(__APPLE__)
#define GIRO360_EXPORT extern "C" __attribute__((visibility("default"))) __attribute__((used))
#else
#define GIRO360_EXPORT extern "C"
#endif

GIRO360_EXPORT void giro360_stitch_link_anchor() {}

int run_stitch(
    const char** image_paths,
    int image_count,
    const double* actual_yaws,
    const double* actual_pitches,
    int guided_fill_mode,
    int guided_alignment_mode,
    const char* output_path,
    char* error_buffer,
    int error_buffer_length) {
  try {
    if (image_paths == nullptr || output_path == nullptr) {
      write_error(error_buffer, error_buffer_length, "Parâmetros nulos enviados ao OpenCV.");
      return 1;
    }

    std::vector<cv::Mat> images = load_images(image_paths, image_count);

    std::string orb_warning;
    const bool passed_orb_check = quick_orb_overlap_check(images, &orb_warning);
    if (!passed_orb_check || image_count > kMaxImagesForFeatureStitching) {
      GuidedRefinementMetrics refinement_metrics;
      cv::Mat guided_fallback =
          build_guided_cylindrical_fallback(
              images,
              actual_yaws,
              actual_pitches,
              guided_fill_mode,
              guided_alignment_mode,
              &refinement_metrics);
      if (!cv::imwrite(output_path, guided_fallback)) {
        write_error(error_buffer, error_buffer_length, "Não foi possível salvar o panorama guiado.");
        return 5;
      }

      const std::string warning = passed_orb_check
          ? "Usei o modo guiado para evitar uma costura pesada com muitos frames no MVP."
          : "Usei o modo guiado porque a cena não tinha textura suficiente: " + orb_warning;
      write_error(
          error_buffer,
          error_buffer_length,
          warning + format_guided_refinement_metrics(refinement_metrics));
      return kGuidedFallbackCode;
    }

    cv::Ptr<cv::Stitcher> stitcher = cv::Stitcher::create(cv::Stitcher::PANORAMA);
    stitcher->setFeaturesFinder(cv::ORB::create(5000));
    stitcher->setPanoConfidenceThresh(0.25);
    stitcher->setWaveCorrection(true);
    stitcher->setInterpolationFlags(cv::INTER_LINEAR);

    cv::Mat panorama;
    const cv::Stitcher::Status status = stitcher->stitch(images, panorama);
    if (status != cv::Stitcher::OK) {
      GuidedRefinementMetrics refinement_metrics;
      cv::Mat guided_fallback =
          build_guided_cylindrical_fallback(
              images,
              actual_yaws,
              actual_pitches,
              guided_fill_mode,
              guided_alignment_mode,
              &refinement_metrics);
      if (!cv::imwrite(output_path, guided_fallback)) {
        write_error(error_buffer, error_buffer_length, stitcher_status_to_string(status));
        return 3;
      }

      const std::string warning = passed_orb_check
          ? stitcher_status_to_string(status)
          : orb_warning;
      write_error(
          error_buffer,
          error_buffer_length,
          "Usei o modo guiado porque a costura por pontos falhou: " + warning +
              format_guided_refinement_metrics(refinement_metrics));
      return kGuidedFallbackCode;
    }

    if (panorama.empty()) {
      write_error(error_buffer, error_buffer_length, "O panorama final saiu vazio.");
      return 4;
    }

    cv::Mat equirectangular = force_equirectangular_ratio(panorama);

    if (!cv::imwrite(output_path, equirectangular)) {
      write_error(error_buffer, error_buffer_length, "Não foi possível salvar o JPEG final.");
      return 5;
    }

    return 0;
  } catch (const std::exception& error) {
    write_error(error_buffer, error_buffer_length, error.what());
    return 10;
  } catch (...) {
    write_error(error_buffer, error_buffer_length, "Erro nativo desconhecido.");
    return 11;
  }
}

GIRO360_EXPORT int giro360_stitch(
    const char** image_paths,
    int image_count,
    const char* output_path,
    char* error_buffer,
    int error_buffer_length) {
  return run_stitch(
      image_paths,
      image_count,
      nullptr,
      nullptr,
      kGuidedFillBlackBands,
      kGuidedAlignmentHorizontalRefine,
      output_path,
      error_buffer,
      error_buffer_length);
}

GIRO360_EXPORT int giro360_stitch_with_telemetry(
    const char** image_paths,
    int image_count,
    const double* actual_yaws,
    const double* actual_pitches,
    const char* output_path,
    char* error_buffer,
    int error_buffer_length) {
  return run_stitch(
      image_paths,
      image_count,
      actual_yaws,
      actual_pitches,
      kGuidedFillBlackBands,
      kGuidedAlignmentHorizontalRefine,
      output_path,
      error_buffer,
      error_buffer_length);
}

GIRO360_EXPORT int giro360_stitch_with_options(
    const char** image_paths,
    int image_count,
    const double* actual_yaws,
    const double* actual_pitches,
    int guided_fill_mode,
    const char* output_path,
    char* error_buffer,
    int error_buffer_length) {
  return run_stitch(
      image_paths,
      image_count,
      actual_yaws,
      actual_pitches,
      guided_fill_mode,
      kGuidedAlignmentHorizontalRefine,
      output_path,
      error_buffer,
      error_buffer_length);
}

GIRO360_EXPORT int giro360_stitch_with_options_v2(
    const char** image_paths,
    int image_count,
    const double* actual_yaws,
    const double* actual_pitches,
    int guided_fill_mode,
    int guided_alignment_mode,
    const char* output_path,
    char* error_buffer,
    int error_buffer_length) {
  return run_stitch(
      image_paths,
      image_count,
      actual_yaws,
      actual_pitches,
      guided_fill_mode,
      guided_alignment_mode,
      output_path,
      error_buffer,
      error_buffer_length);
}
