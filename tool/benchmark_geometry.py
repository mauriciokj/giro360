#!/usr/bin/env python3
"""Compare feature, robust-estimation and geometric models for a capture."""

from __future__ import annotations

import argparse
import csv
import json
import math
from pathlib import Path
from typing import Any

import cv2
import numpy as np


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--timeline", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--max-width", type=int, default=1280)
    parser.add_argument("--worst-count", type=int, default=6)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    root = json.loads(args.timeline.read_text())
    frames = sorted(root["frames"], key=lambda frame: frame["binIndex"])
    session = args.timeline.parent
    calibration = root.get("cameraCalibration", {})
    images: list[np.ndarray] = []
    scales: list[float] = []
    for frame in frames:
        path = session / Path(frame["filePath"]).name
        image = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
        if image is None:
            raise FileNotFoundError(path)
        scale = min(1.0, args.max_width / image.shape[1])
        if scale < 1.0:
            image = cv2.resize(image, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
        images.append(image)
        scales.append(scale)

    camera_matrix = _camera_matrix_for_image(
        calibration,
        original_width=int(round(images[0].shape[1] / scales[0])),
        original_height=int(round(images[0].shape[0] / scales[0])),
    )

    rows: list[dict[str, Any]] = []
    visual_candidates: list[tuple[float, dict[str, Any], list[cv2.DMatch], list[cv2.KeyPoint], list[cv2.KeyPoint], np.ndarray]] = []
    configurations = [
        ("orb", cv2.ORB_create(nfeatures=4000, fastThreshold=10), cv2.NORM_HAMMING, 0.80),
        ("sift", cv2.SIFT_create(nfeatures=4000, contrastThreshold=0.025), cv2.NORM_L2, 0.75),
    ]
    robust_methods = [("ransac", cv2.RANSAC), ("usac_magsac", cv2.USAC_MAGSAC)]

    for feature_name, detector, norm, ratio_threshold in configurations:
        features = [detector.detectAndCompute(image, None) for image in images]
        matcher = cv2.BFMatcher(norm)
        for pair_index in range(len(frames)):
            next_index = (pair_index + 1) % len(frames)
            keypoints_a, descriptors_a = features[pair_index]
            keypoints_b, descriptors_b = features[next_index]
            if descriptors_a is None or descriptors_b is None:
                continue
            candidates = matcher.knnMatch(descriptors_a, descriptors_b, k=2)
            matches = [first for first, second in candidates if first.distance < ratio_threshold * second.distance]
            if len(matches) < 8:
                for robust_name, _ in robust_methods:
                    rows.append(_empty_row(feature_name, robust_name, pair_index, next_index, len(keypoints_a), len(keypoints_b), len(matches)))
                continue

            points_a = np.float64([keypoints_a[match.queryIdx].pt for match in matches])
            points_b = np.float64([keypoints_b[match.trainIdx].pt for match in matches])
            scaled_camera = camera_matrix.copy()
            scaled_camera[0, :] *= scales[pair_index]
            scaled_camera[1, :] *= scales[pair_index]

            for robust_name, robust_method in robust_methods:
                row = {
                    "feature": feature_name,
                    "robustMethod": robust_name,
                    "pair": f"{pair_index}->{next_index}",
                    "fromBin": pair_index,
                    "toBin": next_index,
                    "keypointsA": len(keypoints_a),
                    "keypointsB": len(keypoints_b),
                    "goodMatches": len(matches),
                    "translationMeters": max(float(frames[pair_index].get("translationMeters", 0.0)), float(frames[next_index].get("translationMeters", 0.0))),
                }
                homography, homography_mask = cv2.findHomography(
                    points_a,
                    points_b,
                    robust_method,
                    3.0,
                    maxIters=5000,
                    confidence=0.999,
                )
                fundamental, fundamental_mask = cv2.findFundamentalMat(
                    points_a,
                    points_b,
                    robust_method,
                    1.5,
                    0.999,
                    5000,
                )
                essential, essential_mask = cv2.findEssentialMat(
                    points_a,
                    points_b,
                    scaled_camera,
                    method=robust_method,
                    prob=0.999,
                    threshold=1.5,
                    maxIters=5000,
                )
                row.update(_homography_metrics(homography, homography_mask, points_a, points_b, images[pair_index].shape))
                row.update(_fundamental_metrics(fundamental, fundamental_mask, points_a, points_b, images[pair_index].shape))
                row.update(_essential_metrics(essential, essential_mask, points_a, points_b, scaled_camera))
                row["recommendedModel"] = _recommended_model(row)
                rows.append(row)

                if feature_name == "sift" and robust_name == "usac_magsac":
                    risk = _visual_risk(row)
                    mask = np.asarray(homography_mask).ravel().astype(bool) if homography_mask is not None else np.zeros(len(matches), dtype=bool)
                    visual_candidates.append((risk, row, matches, keypoints_a, keypoints_b, mask))

    summary = _build_summary(rows)
    report = {
        "sourceTimeline": str(args.timeline.resolve()),
        "frameCount": len(frames),
        "imageSize": [int(images[0].shape[1]), int(images[0].shape[0])],
        "cameraMatrix": camera_matrix.tolist(),
        "summary": summary,
        "pairs": rows,
    }
    (args.output / "geometry_benchmark.json").write_text(json.dumps(report, indent=2))
    _write_csv(args.output / "geometry_benchmark.csv", rows)
    _write_worst_visuals(args.output, visual_candidates, images, args.worst_count)
    print(json.dumps(summary, indent=2))
    print(f"report={args.output / 'geometry_benchmark.json'}")


def _empty_row(feature: str, robust: str, first: int, second: int, keypoints_a: int, keypoints_b: int, matches: int) -> dict[str, Any]:
    return {
        "feature": feature,
        "robustMethod": robust,
        "pair": f"{first}->{second}",
        "fromBin": first,
        "toBin": second,
        "keypointsA": keypoints_a,
        "keypointsB": keypoints_b,
        "goodMatches": matches,
        "homographyInliers": 0,
        "homographyInlierRatio": 0.0,
        "homographyCoverage": 0.0,
        "homographyMedianErrorPixels": None,
        "fundamentalInliers": 0,
        "fundamentalInlierRatio": 0.0,
        "fundamentalCoverage": 0.0,
        "fundamentalMedianErrorPixels": None,
        "essentialInliers": 0,
        "essentialInlierRatio": 0.0,
        "essentialRotationDegrees": None,
        "recommendedModel": "insufficient_matches",
    }


def _camera_matrix_for_image(
    calibration: dict[str, Any],
    original_width: int,
    original_height: int,
) -> np.ndarray:
    source_width = int(calibration.get("imageWidth", original_width))
    source_height = int(calibration.get("imageHeight", original_height))
    fx = float(calibration.get("meanFxPixels", 1.0))
    fy = float(calibration.get("meanFyPixels", 1.0))
    cx = float(calibration.get("meanCxPixels", source_width / 2.0))
    cy = float(calibration.get("meanCyPixels", source_height / 2.0))
    if original_width == source_height and original_height == source_width:
        fx, fy = fy, fx
        cx, cy = source_height - 1.0 - cy, cx
    return np.array(
        [[fx, 0.0, cx], [0.0, fy, cy], [0.0, 0.0, 1.0]],
        dtype=np.float64,
    )


def _homography_metrics(homography: np.ndarray | None, mask: np.ndarray | None, points_a: np.ndarray, points_b: np.ndarray, shape: tuple[int, int]) -> dict[str, Any]:
    if homography is None or mask is None:
        return {"homographyInliers": 0, "homographyInlierRatio": 0.0, "homographyCoverage": 0.0, "homographyMedianErrorPixels": None}
    inliers = mask.ravel().astype(bool)
    projected = cv2.perspectiveTransform(points_a.reshape(-1, 1, 2), homography).reshape(-1, 2)
    errors = np.linalg.norm(projected - points_b, axis=1)
    return {
        "homographyInliers": int(inliers.sum()),
        "homographyInlierRatio": float(inliers.mean()),
        "homographyCoverage": _coverage(points_a[inliers], shape),
        "homographyMedianErrorPixels": float(np.median(errors[inliers])) if inliers.any() else None,
    }


def _fundamental_metrics(fundamental: np.ndarray | None, mask: np.ndarray | None, points_a: np.ndarray, points_b: np.ndarray, shape: tuple[int, int]) -> dict[str, Any]:
    if fundamental is None or mask is None or fundamental.shape != (3, 3):
        return {"fundamentalInliers": 0, "fundamentalInlierRatio": 0.0, "fundamentalCoverage": 0.0, "fundamentalMedianErrorPixels": None}
    inliers = mask.ravel().astype(bool)
    points_a_h = np.column_stack([points_a, np.ones(len(points_a))])
    points_b_h = np.column_stack([points_b, np.ones(len(points_b))])
    lines_b = (fundamental @ points_a_h.T).T
    numerators = np.abs(np.sum(points_b_h * lines_b, axis=1))
    denominators = np.sqrt(lines_b[:, 0] ** 2 + lines_b[:, 1] ** 2) + 1e-9
    errors = numerators / denominators
    return {
        "fundamentalInliers": int(inliers.sum()),
        "fundamentalInlierRatio": float(inliers.mean()),
        "fundamentalCoverage": _coverage(points_a[inliers], shape),
        "fundamentalMedianErrorPixels": float(np.median(errors[inliers])) if inliers.any() else None,
    }


def _essential_metrics(essential: np.ndarray | None, mask: np.ndarray | None, points_a: np.ndarray, points_b: np.ndarray, camera_matrix: np.ndarray) -> dict[str, Any]:
    if essential is None or mask is None:
        return {"essentialInliers": 0, "essentialInlierRatio": 0.0, "essentialRotationDegrees": None}
    if essential.shape != (3, 3):
        essential = essential[:3, :3]
    try:
        count, rotation, _, pose_mask = cv2.recoverPose(essential, points_a, points_b, camera_matrix, mask=mask)
        trace = np.clip((np.trace(rotation) - 1.0) / 2.0, -1.0, 1.0)
        rotation_degrees = math.degrees(math.acos(trace))
        pose_ratio = float(np.asarray(pose_mask).ravel().astype(bool).mean())
        return {"essentialInliers": int(count), "essentialInlierRatio": pose_ratio, "essentialRotationDegrees": rotation_degrees}
    except cv2.error:
        return {"essentialInliers": 0, "essentialInlierRatio": 0.0, "essentialRotationDegrees": None}


def _coverage(points: np.ndarray, shape: tuple[int, int], columns: int = 4, rows: int = 3) -> float:
    if len(points) == 0:
        return 0.0
    height, width = shape
    cells = set()
    for x, y in points:
        column = min(columns - 1, max(0, int(x / width * columns)))
        row = min(rows - 1, max(0, int(y / height * rows)))
        cells.add((column, row))
    return len(cells) / (columns * rows)


def _recommended_model(row: dict[str, Any]) -> str:
    if row["goodMatches"] < 12:
        return "insufficient_matches"
    homography_ratio = row["homographyInlierRatio"]
    fundamental_ratio = row["fundamentalInlierRatio"]
    homography_error = row["homographyMedianErrorPixels"]
    if homography_error is not None and homography_error <= 1.8 and homography_ratio >= 0.72 and homography_ratio >= fundamental_ratio * 0.86:
        return "homography"
    if fundamental_ratio >= 0.55:
        return "epipolar"
    return "uncertain"


def _visual_risk(row: dict[str, Any]) -> float:
    error = row["homographyMedianErrorPixels"] or 10.0
    return error * 2.0 + (1.0 - row["homographyInlierRatio"]) * 10.0 + (1.0 - row["homographyCoverage"]) * 4.0


def _build_summary(rows: list[dict[str, Any]]) -> dict[str, Any]:
    summary: dict[str, Any] = {}
    for feature in ("orb", "sift"):
        for robust in ("ransac", "usac_magsac"):
            selected = [row for row in rows if row["feature"] == feature and row["robustMethod"] == robust]
            key = f"{feature}_{robust}"
            summary[key] = {
                "pairCount": len(selected),
                "medianGoodMatches": _median([row["goodMatches"] for row in selected]),
                "medianHomographyInlierRatio": _median([row["homographyInlierRatio"] for row in selected]),
                "medianHomographyCoverage": _median([row["homographyCoverage"] for row in selected]),
                "medianHomographyErrorPixels": _median([row["homographyMedianErrorPixels"] for row in selected if row["homographyMedianErrorPixels"] is not None]),
                "medianFundamentalInlierRatio": _median([row["fundamentalInlierRatio"] for row in selected]),
                "recommendedHomographyPairs": sum(row["recommendedModel"] == "homography" for row in selected),
                "recommendedEpipolarPairs": sum(row["recommendedModel"] == "epipolar" for row in selected),
                "uncertainPairs": sum(row["recommendedModel"] not in ("homography", "epipolar") for row in selected),
            }
    return summary


def _median(values: list[float | int]) -> float | None:
    return float(np.median(values)) if values else None


def _write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    fields = sorted({field for row in rows for field in row})
    with path.open("w", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def _write_worst_visuals(output: Path, candidates: list[tuple[Any, ...]], images: list[np.ndarray], count: int) -> None:
    diagnostics = output / "worst_pairs"
    diagnostics.mkdir(exist_ok=True)
    for rank, (_, row, matches, keypoints_a, keypoints_b, mask) in enumerate(sorted(candidates, reverse=True, key=lambda item: item[0])[:count], start=1):
        first = row["fromBin"]
        second = row["toBin"]
        inlier_matches = [match for match, keep in zip(matches, mask) if keep]
        rendered = cv2.drawMatches(
            cv2.cvtColor(images[first], cv2.COLOR_GRAY2BGR),
            keypoints_a,
            cv2.cvtColor(images[second], cv2.COLOR_GRAY2BGR),
            keypoints_b,
            inlier_matches[:100],
            None,
            flags=cv2.DrawMatchesFlags_NOT_DRAW_SINGLE_POINTS,
        )
        cv2.imwrite(str(diagnostics / f"{rank:02d}_pair_{first:02d}_{second:02d}.jpg"), rendered)


if __name__ == "__main__":
    main()
