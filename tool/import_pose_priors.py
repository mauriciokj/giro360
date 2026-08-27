#!/usr/bin/env python3
"""Import Cartesian camera positions from a Giro360 timeline into COLMAP."""

from __future__ import annotations

import argparse
import json
import sqlite3
import struct
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--timeline", required=True, type=Path)
    parser.add_argument("--database", required=True, type=Path)
    parser.add_argument("--standard-deviation", type=float, default=0.10)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.standard_deviation <= 0:
        raise ValueError("standard deviation must be positive")

    timeline = json.loads(args.timeline.read_text())
    frames = sorted(timeline["frames"], key=lambda frame: frame["binIndex"])
    covariance = _matrix_blob(
        [
            args.standard_deviation**2,
            0.0,
            0.0,
            0.0,
            args.standard_deviation**2,
            0.0,
            0.0,
            0.0,
            args.standard_deviation**2,
        ]
    )

    connection = sqlite3.connect(args.database)
    try:
        images = {
            name: (image_id, camera_id)
            for image_id, name, camera_id in connection.execute(
                "SELECT image_id, name, camera_id FROM images"
            )
        }
        rows = []
        for frame in frames:
            name = Path(frame["filePath"]).name
            if name not in images:
                raise RuntimeError(f"image not found in COLMAP database: {name}")
            transform = frame.get("cameraTransform")
            if not isinstance(transform, list) or len(transform) != 16:
                raise RuntimeError(f"camera transform unavailable for {name}")
            image_id, camera_id = images[name]
            position = _matrix_blob(
                [float(transform[12]), float(transform[13]), float(transform[14])]
            )
            rows.append(
                (
                    image_id,
                    camera_id,
                    0,  # Camera sensor.
                    position,
                    covariance,
                    None,
                    1,  # Cartesian coordinate system.
                )
            )

        connection.execute("DELETE FROM pose_priors")
        connection.executemany(
            """
            INSERT INTO pose_priors (
              corr_data_id,
              corr_sensor_id,
              corr_sensor_type,
              position,
              position_covariance,
              gravity,
              coordinate_system
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            rows,
        )
        connection.commit()
    finally:
        connection.close()

    print(f"pose_priors={len(rows)}")
    print(f"standard_deviation_meters={args.standard_deviation:.6f}")
    print(f"database={args.database.resolve()}")


def _matrix_blob(values: list[float]) -> bytes:
    return struct.pack(f"<{len(values)}d", *values)


if __name__ == "__main__":
    main()
