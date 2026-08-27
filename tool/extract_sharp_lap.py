#!/usr/bin/env python3
"""Select a coherent panorama lap while preferring genuinely sharp video frames."""

from __future__ import annotations

import argparse
import json
import math
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np


@dataclass
class Candidate:
    frame_index: int
    time: float
    yaw: float
    pitch: float
    roll: float
    translation: float
    speed: float
    sharpness: float
    image: np.ndarray


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--timeline", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--lap", type=int, default=1, choices=(1, 2))
    parser.add_argument("--bins", type=int, default=60)
    parser.add_argument("--search-fraction", type=float, default=0.72)
    return parser.parse_args()


def tracked_samples(root: dict) -> dict[str, np.ndarray]:
    samples = [item for item in root["timeline"] if item["trackingState"] == "normal"]
    samples.sort(key=lambda item: item["videoTimeSeconds"])
    unique = []
    for sample in samples:
        if unique and abs(sample["videoTimeSeconds"] - unique[-1]["videoTimeSeconds"]) < 1e-6:
            unique[-1] = sample
        else:
            unique.append(sample)
    keys = (
        "videoTimeSeconds",
        "relativeYawRadians",
        "pitchRadians",
        "rollRadians",
        "translationMeters",
        "angularSpeedRadiansPerSecond",
    )
    return {key: np.asarray([item[key] for item in unique], dtype=np.float64) for key in keys}


def interpolate(samples: dict[str, np.ndarray], timestamp: float, key: str) -> float:
    return float(np.interp(timestamp, samples["videoTimeSeconds"], samples[key]))


def sharpness(image: np.ndarray) -> float:
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    if gray.shape[1] > 720:
        scale = 720.0 / gray.shape[1]
        gray = cv2.resize(gray, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
    return float(cv2.Laplacian(gray, cv2.CV_32F).var())


def collect_candidates(video: Path, samples: dict[str, np.ndarray], lap: int) -> list[Candidate]:
    lap_start = (lap - 1) * math.tau
    lap_end = lap * math.tau
    capture = cv2.VideoCapture(str(video))
    fps = capture.get(cv2.CAP_PROP_FPS)
    result = []
    frame_index = 0
    while True:
        ok, image = capture.read()
        if not ok:
            break
        timestamp = frame_index / fps
        yaw = interpolate(samples, timestamp, "relativeYawRadians")
        if lap_start - 0.2 <= yaw <= lap_end + 0.2:
            result.append(
                Candidate(
                    frame_index=frame_index,
                    time=timestamp,
                    yaw=yaw,
                    pitch=interpolate(samples, timestamp, "pitchRadians"),
                    roll=interpolate(samples, timestamp, "rollRadians"),
                    translation=interpolate(samples, timestamp, "translationMeters"),
                    speed=interpolate(samples, timestamp, "angularSpeedRadiansPerSecond"),
                    sharpness=sharpness(image),
                    image=image,
                )
            )
        frame_index += 1
    capture.release()
    return result


def choose_frames(
    candidates: list[Candidate],
    lap: int,
    bins: int,
    search_fraction: float,
) -> list[Candidate]:
    step = math.tau / bins
    start = (lap - 1) * math.tau
    sharp_values = np.asarray([math.log1p(item.sharpness) for item in candidates])
    sharp_low, sharp_high = np.percentile(sharp_values, [5, 95])
    sharp_range = max(float(sharp_high - sharp_low), 1e-6)
    speeds = np.asarray([item.speed for item in candidates])
    speed_scale = max(float(np.median(speeds)), 0.05)
    selected_by_bin: dict[int, Candidate] = {}
    previous_index = -1
    selection_order = [*range(1, bins), 0]
    for bin_index in selection_order:
        target = start + (math.tau if bin_index == 0 else bin_index * step)
        nearby = [
            item
            for item in candidates
            if item.frame_index > previous_index
            and abs(item.yaw - target) <= step * search_fraction
        ]
        if not nearby:
            nearby = [item for item in candidates if item.frame_index > previous_index]
        if not nearby:
            raise RuntimeError(f"No candidate remains for bin {bin_index}")

        def cost(item: Candidate) -> float:
            angle = abs(item.yaw - target) / step
            normalized_sharpness = np.clip(
                (math.log1p(item.sharpness) - sharp_low) / sharp_range,
                0,
                1,
            )
            motion = item.speed / speed_scale
            level = (abs(item.pitch) + abs(item.roll)) / math.radians(6)
            return angle * 1.00 + motion * 0.08 + level * 0.06 - normalized_sharpness * 0.34

        best = min(nearby, key=cost)
        selected_by_bin[bin_index] = best
        previous_index = best.frame_index
    return [selected_by_bin[index] for index in range(bins)]


def write_result(root: dict, selected: list[Candidate], output: Path, lap: int) -> Path:
    output.mkdir(parents=True, exist_ok=True)
    step = math.tau / len(selected)
    frames = []
    for bin_index, candidate in enumerate(selected):
        image_path = output / f"video_{bin_index:03d}.jpg"
        cv2.imwrite(str(image_path), candidate.image, [cv2.IMWRITE_JPEG_QUALITY, 96])
        frames.append(
            {
                "binIndex": bin_index,
                "lapIndex": lap - 1,
                "filePath": str(image_path.resolve()),
                "targetYawRadians": bin_index * step,
                "relativeYawRadians": candidate.yaw % math.tau,
                "pitchRadians": candidate.pitch,
                "rollRadians": candidate.roll,
                "translationMeters": candidate.translation,
                "qualityScore": candidate.sharpness,
                "sharpnessScore": candidate.sharpness,
                "angularSpeedRadiansPerSecond": candidate.speed,
                "centerErrorRadians": abs(candidate.yaw - ((lap - 1) * math.tau + (math.tau if bin_index == 0 else bin_index * step))),
                "trackingState": "normal",
                "frameTimestampSeconds": candidate.time,
                "videoTimeSeconds": candidate.time,
                "selectionSource": "offline_sharp_coherent_lap",
            }
        )
    result = dict(root)
    result["selectedLap"] = lap
    result["binCount"] = len(selected)
    result["selectedFrameStartSeconds"] = min(item.time for item in selected)
    result["selectedFrameEndSeconds"] = max(item.time for item in selected)
    result["frames"] = frames
    timeline = output / "timeline.json"
    timeline.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return timeline


def main() -> None:
    args = parse_args()
    root = json.loads(args.timeline.read_text(encoding="utf-8"))
    samples = tracked_samples(root)
    video = args.timeline.parent / "giro360_capture.mp4"
    candidates = collect_candidates(video, samples, args.lap)
    selected = choose_frames(candidates, args.lap, args.bins, args.search_fraction)
    timeline = write_result(root, selected, args.output, args.lap)
    values = np.asarray([item.sharpness for item in selected])
    print(f"timeline={timeline}")
    print(f"candidates={len(candidates)} selected={len(selected)}")
    print(f"sharpness_median={np.median(values):.3f}")
    print(f"sharpness_min={np.min(values):.3f}")
    print(f"sharpness_max={np.max(values):.3f}")


if __name__ == "__main__":
    main()
