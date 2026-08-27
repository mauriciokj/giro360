#!/usr/bin/env python3
"""Compare monocular depth around geometrically difficult adjacent frames."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import cv2
import numpy as np


MODEL_WIDTH = 518
MODEL_HEIGHT = 392


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--frames", required=True, type=Path)
    parser.add_argument("--depth", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument(
        "--pairs",
        default="9:10,10:11,47:48,7:8,35:36,21:22",
        help="Comma-separated frame pairs, for example 9:10,10:11",
    )
    return parser.parse_args()


def frame_path(directory: Path, index: int) -> Path:
    return directory / f"video_{index:03d}.jpg"


def depth_path(directory: Path, index: int) -> Path:
    return directory / f"video_{index:03d}.depth.f32"


def load_depth(path: Path) -> np.ndarray:
    values = np.fromfile(path, dtype=np.float32)
    expected = MODEL_WIDTH * MODEL_HEIGHT
    if values.size != expected:
        raise ValueError(f"{path}: expected {expected} values, found {values.size}")
    return values.reshape(MODEL_HEIGHT, MODEL_WIDTH)


def model_crop(image: np.ndarray) -> tuple[np.ndarray, float, float]:
    height, width = image.shape[:2]
    crop_height = width * MODEL_HEIGHT / MODEL_WIDTH
    top = (height - crop_height) / 2.0
    cropped = image[int(round(top)) : int(round(top + crop_height)), :]
    return cv2.resize(cropped, (MODEL_WIDTH, MODEL_HEIGHT)), top, crop_height


def image_to_depth(points: np.ndarray, width: int, top: float, crop_height: float) -> np.ndarray:
    mapped = points.astype(np.float32).copy()
    mapped[:, 0] *= MODEL_WIDTH / width
    mapped[:, 1] = (mapped[:, 1] - top) * MODEL_HEIGHT / crop_height
    return mapped


def sample_bilinear(image: np.ndarray, points: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    x = points[:, 0]
    y = points[:, 1]
    valid = (x >= 0) & (x < image.shape[1] - 1) & (y >= 0) & (y < image.shape[0] - 1)
    x0 = np.floor(x[valid]).astype(np.int32)
    y0 = np.floor(y[valid]).astype(np.int32)
    dx = x[valid] - x0
    dy = y[valid] - y0
    sampled = (
        image[y0, x0] * (1 - dx) * (1 - dy)
        + image[y0, x0 + 1] * dx * (1 - dy)
        + image[y0 + 1, x0] * (1 - dx) * dy
        + image[y0 + 1, x0 + 1] * dx * dy
    )
    return sampled, valid


def robust_affine(source: np.ndarray, target: np.ndarray) -> tuple[float, float, np.ndarray]:
    keep = np.isfinite(source) & np.isfinite(target)
    source = source[keep]
    target = target[keep]
    if source.size < 8:
        return 1.0, 0.0, np.zeros(source.size, dtype=bool)
    inliers = np.ones(source.size, dtype=bool)
    design = np.column_stack([source, np.ones_like(source)])
    for _ in range(5):
        scale, offset = np.linalg.lstsq(design[inliers], target[inliers], rcond=None)[0]
        residual = np.abs(target - (source * scale + offset))
        median = np.median(residual[inliers])
        mad = np.median(np.abs(residual[inliers] - median))
        threshold = max(0.02, median + 3.0 * 1.4826 * mad)
        next_inliers = residual <= threshold
        if next_inliers.sum() < 8 or np.array_equal(next_inliers, inliers):
            break
        inliers = next_inliers
    return float(scale), float(offset), inliers


def colorize_depth(depth: np.ndarray) -> np.ndarray:
    low, high = np.percentile(depth[np.isfinite(depth)], [2, 98])
    normalized = np.clip((depth - low) / max(high - low, 1e-6), 0, 1)
    return cv2.applyColorMap(np.uint8(normalized * 255), cv2.COLORMAP_TURBO)


def add_label(image: np.ndarray, label: str) -> np.ndarray:
    result = image.copy()
    cv2.rectangle(result, (0, 0), (result.shape[1], 38), (18, 18, 18), -1)
    cv2.putText(result, label, (12, 26), cv2.FONT_HERSHEY_SIMPLEX, 0.62, (255, 255, 255), 1, cv2.LINE_AA)
    return result


def analyze_pair(frames: Path, depths: Path, first: int, second: int) -> tuple[dict, np.ndarray]:
    image_a = cv2.imread(str(frame_path(frames, first)))
    image_b = cv2.imread(str(frame_path(frames, second)))
    if image_a is None or image_b is None:
        raise FileNotFoundError(f"Could not load pair {first}:{second}")
    gray_a = cv2.cvtColor(image_a, cv2.COLOR_BGR2GRAY)
    gray_b = cv2.cvtColor(image_b, cv2.COLOR_BGR2GRAY)
    sift = cv2.SIFT_create(nfeatures=4000)
    keys_a, desc_a = sift.detectAndCompute(gray_a, None)
    keys_b, desc_b = sift.detectAndCompute(gray_b, None)
    matches = cv2.BFMatcher(cv2.NORM_L2).knnMatch(desc_a, desc_b, k=2)
    good = [first_match for first_match, second_match in matches if first_match.distance < 0.75 * second_match.distance]
    points_a = np.float32([keys_a[item.queryIdx].pt for item in good])
    points_b = np.float32([keys_b[item.trainIdx].pt for item in good])
    _, mask = cv2.findHomography(points_b, points_a, cv2.USAC_MAGSAC, 3.0)
    geometric = mask.ravel().astype(bool) if mask is not None else np.zeros(len(good), dtype=bool)
    points_a = points_a[geometric]
    points_b = points_b[geometric]

    crop_a, top_a, crop_height_a = model_crop(image_a)
    crop_b, top_b, crop_height_b = model_crop(image_b)
    mapped_a = image_to_depth(points_a, image_a.shape[1], top_a, crop_height_a)
    mapped_b = image_to_depth(points_b, image_b.shape[1], top_b, crop_height_b)
    depth_a = load_depth(depth_path(depths, first))
    depth_b = load_depth(depth_path(depths, second))
    sampled_a, valid_a = sample_bilinear(depth_a, mapped_a)
    sampled_b, valid_b = sample_bilinear(depth_b, mapped_b)
    valid = valid_a & valid_b
    sampled_a = sampled_a[valid[valid_a]]
    sampled_b = sampled_b[valid[valid_b]]
    visible_a = mapped_a[valid]
    scale, offset, depth_inliers = robust_affine(sampled_b, sampled_a)
    residual = np.abs(sampled_a - (sampled_b * scale + offset))
    depth_range = max(float(np.percentile(sampled_a, 95) - np.percentile(sampled_a, 5)), 1e-6)
    normalized = residual / depth_range

    overlay = crop_a.copy()
    for point, error in zip(visible_a, normalized):
        color = (0, 220, 0) if error < 0.08 else ((0, 200, 255) if error < 0.18 else (0, 0, 255))
        cv2.circle(overlay, tuple(np.round(point).astype(int)), 4, color, -1, cv2.LINE_AA)
    panels = [
        add_label(crop_a, f"quadro {first}"),
        add_label(crop_b, f"quadro {second}"),
        add_label(colorize_depth(depth_a), "profundidade A"),
        add_label(overlay, "verde ok | amarelo/verm. conflito"),
    ]
    row = np.hstack(panels)
    metrics = {
        "pair": f"{first}:{second}",
        "ratio_matches": len(good),
        "homography_inliers": int(geometric.sum()),
        "depth_samples": int(normalized.size),
        "depth_affine_scale": scale,
        "depth_affine_offset": offset,
        "depth_fit_inliers": int(depth_inliers.sum()),
        "normalized_depth_error_median": float(np.median(normalized)),
        "normalized_depth_error_p90": float(np.percentile(normalized, 90)),
        "conflict_ratio_over_0_18": float(np.mean(normalized >= 0.18)),
    }
    return metrics, row


def main() -> None:
    args = parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    pairs = [tuple(map(int, item.split(":"))) for item in args.pairs.split(",")]
    metrics = []
    rows = []
    for first, second in pairs:
        pair_metrics, row = analyze_pair(args.frames, args.depth, first, second)
        metrics.append(pair_metrics)
        rows.append(row)
        print(json.dumps(pair_metrics, ensure_ascii=False))
    sheet = np.vstack(rows)
    cv2.imwrite(str(args.output / "depth_critical_pairs.jpg"), sheet, [cv2.IMWRITE_JPEG_QUALITY, 94])
    (args.output / "depth_critical_pairs.json").write_text(
        json.dumps({"pairs": metrics}, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
