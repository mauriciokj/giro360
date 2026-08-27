#!/usr/bin/env python3
"""Replace timeline yaw and pitch with COLMAP global camera rotations."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

import numpy as np


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--timeline", required=True, type=Path)
    parser.add_argument("--images", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--mode", choices=("raw", "yaw_fused", "fused"), default="fused")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    timeline = json.loads(args.timeline.read_text())
    frames = sorted(timeline["frames"], key=lambda frame: frame["binIndex"])
    poses = _read_colmap_images(args.images)
    missing = [Path(frame["filePath"]).name for frame in frames if Path(frame["filePath"]).name not in poses]
    if missing:
        raise RuntimeError(f"COLMAP model is missing {len(missing)} frames: {missing[:3]}")

    rotations = [poses[Path(frame["filePath"]).name][0] for frame in frames]
    centers = np.array([poses[Path(frame["filePath"]).name][1] for frame in frames])
    forwards = np.array([rotation.T @ np.array([0.0, 0.0, 1.0]) for rotation in rotations])
    axis = _rotation_axis(forwards)
    telemetry_pitch = np.array([float(frame["pitchRadians"]) for frame in frames])
    raw_pitch = np.arcsin(np.clip(forwards @ axis, -1.0, 1.0))
    if np.corrcoef(raw_pitch, telemetry_pitch)[0, 1] < 0:
        axis = -axis
        raw_pitch = -raw_pitch

    telemetry_yaw = np.unwrap(np.array([float(frame["relativeYawRadians"]) for frame in frames]))
    colmap_yaw = _unwrapped_yaw(forwards, axis)
    if np.corrcoef(colmap_yaw, telemetry_yaw)[0, 1] < 0:
        colmap_yaw = _unwrapped_yaw(forwards, -axis)
        axis = -axis
        raw_pitch = -raw_pitch
    slope, offset = np.polyfit(colmap_yaw, telemetry_yaw, 1)
    aligned_yaw = colmap_yaw * slope + offset
    raw_aligned_pitch = raw_pitch - np.mean(raw_pitch) + np.mean(telemetry_pitch)

    yaw_residual = aligned_yaw - telemetry_yaw
    pitch_residual = raw_aligned_pitch - telemetry_pitch
    if args.mode == "raw":
        output_yaw = aligned_yaw
        output_pitch = raw_aligned_pitch
    else:
        yaw_correction = np.clip(_smooth_circular(yaw_residual, 5), math.radians(-4.0), math.radians(4.0))
        output_yaw = telemetry_yaw + yaw_correction
        if args.mode == "yaw_fused":
            output_pitch = telemetry_pitch
        else:
            pitch_correction = np.clip(_smooth_circular(pitch_residual, 5), math.radians(-1.0), math.radians(1.0))
            output_pitch = telemetry_pitch + pitch_correction
    center_deltas = np.linalg.norm(np.diff(centers, axis=0), axis=1)
    diagnostics = {
        "mode": args.mode,
        "registeredFrameCount": len(frames),
        "rotationAxis": axis.tolist(),
        "yawScale": float(slope),
        "yawOffset": float(offset),
        "yawRmseDegrees": float(math.degrees(np.sqrt(np.mean(yaw_residual**2)))),
        "yawMaxErrorDegrees": float(math.degrees(np.max(np.abs(yaw_residual)))),
        "rawPitchRmseDegrees": float(math.degrees(np.sqrt(np.mean(pitch_residual**2)))),
        "outputYawCorrectionRmseDegrees": float(math.degrees(np.sqrt(np.mean((output_yaw - telemetry_yaw) ** 2)))),
        "outputYawCorrectionMaxDegrees": float(math.degrees(np.max(np.abs(output_yaw - telemetry_yaw)))),
        "outputPitchCorrectionRmseDegrees": float(math.degrees(np.sqrt(np.mean((output_pitch - telemetry_pitch) ** 2)))),
        "outputPitchCorrectionMaxDegrees": float(math.degrees(np.max(np.abs(output_pitch - telemetry_pitch)))),
        "pitchSpanDegrees": float(math.degrees(np.ptp(output_pitch))),
        "telemetryPitchSpanDegrees": float(math.degrees(np.ptp(telemetry_pitch))),
        "relativeCameraCenterStepMedian": float(np.median(center_deltas)),
        "relativeCameraCenterStepMax": float(np.max(center_deltas)),
    }

    for index, frame in enumerate(frames):
        frame["telemetryYawRadians"] = frame["relativeYawRadians"]
        frame["telemetryPitchRadians"] = frame["pitchRadians"]
        frame["relativeYawRadians"] = float(_positive_modulo(output_yaw[index], math.tau))
        frame["pitchRadians"] = float(output_pitch[index])
        frame["selectionSource"] = f"colmap_global_pose_{args.mode}"
    timeline["frames"] = frames
    timeline["colmapGlobalPose"] = diagnostics
    args.output.write_text(json.dumps(timeline, indent=2))
    print(json.dumps(diagnostics, indent=2))
    print(f"timeline={args.output}")


def _read_colmap_images(path: Path) -> dict[str, tuple[np.ndarray, np.ndarray]]:
    poses: dict[str, tuple[np.ndarray, np.ndarray]] = {}
    for line in path.read_text().splitlines():
        if not line or line.startswith("#"):
            continue
        fields = line.split()
        if len(fields) != 10 or not fields[-1].lower().endswith((".jpg", ".jpeg", ".png")):
            continue
        quaternion = np.array([float(value) for value in fields[1:5]])
        translation = np.array([float(value) for value in fields[5:8]])
        rotation = _quaternion_rotation(quaternion)
        center = -rotation.T @ translation
        poses[fields[-1]] = (rotation, center)
    return poses


def _quaternion_rotation(quaternion: np.ndarray) -> np.ndarray:
    w, x, y, z = quaternion / np.linalg.norm(quaternion)
    return np.array(
        [
            [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
            [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
            [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)],
        ]
    )


def _rotation_axis(forwards: np.ndarray) -> np.ndarray:
    centered = forwards - np.mean(forwards, axis=0)
    _, _, vectors = np.linalg.svd(centered, full_matrices=False)
    axis = vectors[-1]
    return axis / np.linalg.norm(axis)


def _unwrapped_yaw(forwards: np.ndarray, axis: np.ndarray) -> np.ndarray:
    first = forwards[0] - axis * np.dot(forwards[0], axis)
    first /= np.linalg.norm(first)
    second = np.cross(axis, first)
    angles = np.arctan2(forwards @ second, forwards @ first)
    return np.unwrap(angles)


def _positive_modulo(value: float, modulus: float) -> float:
    return (value % modulus + modulus) % modulus


def _smooth_circular(values: np.ndarray, window: int) -> np.ndarray:
    radius = window // 2
    return np.array(
        [
            np.median([values[(index + offset) % len(values)] for offset in range(-radius, radius + 1)])
            for index in range(len(values))
        ]
    )


if __name__ == "__main__":
    main()
