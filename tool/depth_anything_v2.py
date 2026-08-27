#!/usr/bin/env python3
"""Generate full-frame relative depth maps with Depth Anything V2."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path

import cv2
import numpy as np
import torch
from PIL import Image
from transformers import AutoImageProcessor, AutoModelForDepthEstimation


MODEL_NAME = "depth-anything/Depth-Anything-V2-Small-hf"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--model", default=MODEL_NAME)
    parser.add_argument("--device", choices=("auto", "cpu", "mps"), default="auto")
    parser.add_argument("images", nargs="+", type=Path)
    return parser.parse_args()


def choose_device(requested: str) -> torch.device:
    if requested == "mps" or (requested == "auto" and torch.backends.mps.is_available()):
        return torch.device("mps")
    return torch.device("cpu")


def colorize(depth: np.ndarray) -> np.ndarray:
    finite = depth[np.isfinite(depth)]
    low, high = np.percentile(finite, [2, 98])
    normalized = np.clip((depth - low) / max(float(high - low), 1e-6), 0, 1)
    return cv2.applyColorMap(np.uint8(normalized * 255), cv2.COLORMAP_TURBO)


def normalize_u16(depth: np.ndarray) -> np.ndarray:
    finite = depth[np.isfinite(depth)]
    low, high = np.percentile(finite, [2, 98])
    normalized = np.clip((depth - low) / max(float(high - low), 1e-6), 0, 1)
    return np.uint16(normalized * np.iinfo(np.uint16).max)


def main() -> None:
    args = parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    device = choose_device(args.device)
    processor = AutoImageProcessor.from_pretrained(args.model)
    model = AutoModelForDepthEstimation.from_pretrained(args.model).to(device).eval()
    manifest = {"model": args.model, "device": str(device), "images": []}

    for image_path in args.images:
        image = Image.open(image_path).convert("RGB")
        inputs = processor(images=image, return_tensors="pt")
        inputs = {key: value.to(device) for key, value in inputs.items()}
        with torch.inference_mode():
            prediction = model(**inputs).predicted_depth
        depth = prediction.squeeze().float().cpu().numpy()
        stem = image_path.stem
        raw_path = args.output / f"{stem}.depth.f32"
        preview_path = args.output / f"{stem}.depth.png"
        normalized_path = args.output / f"{stem}.depth.u16.png"
        depth.astype(np.float32).tofile(raw_path)
        cv2.imwrite(str(preview_path), colorize(depth))
        cv2.imwrite(str(normalized_path), normalize_u16(depth))
        item = {
            "image": str(image_path.resolve()),
            "width": int(depth.shape[1]),
            "height": int(depth.shape[0]),
            "raw": raw_path.name,
            "preview": preview_path.name,
            "normalized": normalized_path.name,
            "minimum": float(np.min(depth)),
            "maximum": float(np.max(depth)),
        }
        manifest["images"].append(item)
        print(json.dumps(item, ensure_ascii=False))

    (args.output / "manifest.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
    main()
