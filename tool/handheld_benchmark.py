#!/usr/bin/env python3
"""Offline, bounded-memory replay of recorded iOS captures. Never alters inputs.

Uses decoded frame indices and real presentation timestamps, not index / FPS.
Selections retain native per-frame K and camera transforms. This is a lab tool,
not an automatic quality approval or a change to the mobile capture policy.
"""
import argparse
import ctypes
import hashlib
import json
import math
import time
from pathlib import Path
import cv2
import numpy as np


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')


def fingerprint(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def input_identity(source, pts_file):
    return {name: fingerprint(path) for name, path in {
        'video': source / 'giro360_capture.mp4',
        'timeline': source / 'giro360_video_timeline.json',
        'pts': pts_file,
    }.items()}


def profile(source, pts_file, output):
    timeline = json.loads((source / 'giro360_video_timeline.json').read_text())
    pts = np.array([float(f['best_effort_timestamp_time']) for f in json.loads(pts_file.read_text())['frames']])
    native_times = np.array([math.floor(s['videoTimeSeconds'] * 600) / 600 for s in timeline])
    cap = cv2.VideoCapture(str(source / 'giro360_capture.mp4'))
    cap.set(cv2.CAP_PROP_ORIENTATION_AUTO, 1)
    rows = []
    i = 0
    while cap.grab():
        if i >= len(pts):
            raise RuntimeError('Decoded frames exceed PTS entries')
        timestamp = pts[i]
        native_index = int(np.searchsorted(native_times, timestamp))
        choices = range(max(0, native_index - 1), min(len(timeline), native_index + 2))
        native_index = min(choices, key=lambda n: abs(native_times[n] - timestamp))
        sample = timeline[native_index]
        # About 20 Hz at 60 fps. Keep all original timing and do not interpolate poses.
        if i % 3 == 0 and abs(native_times[native_index] - timestamp) < .002 and sample.get('recorded') and sample['trackingState'] == 'normal':
            ok, image = cap.retrieve()
            if not ok:
                raise RuntimeError(f'Cannot decode frame {i}')
            if image.shape[:2] != (1920, 1440):
                raise RuntimeError(f'Unexpected displayed image orientation: {image.shape}')
            gray = cv2.cvtColor(cv2.resize(image, (360, 480), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2GRAY)
            laplacian = cv2.Laplacian(gray, cv2.CV_32F)
            left = float(laplacian[:, 90:180].var())
            right = float(laplacian[:, 180:270].var())
            rows.append({'frameIndex': i, 'ptsSeconds': float(timestamp), 'timelineIndex': native_index,
                         'sharpness': float(laplacian.var()), 'overlapSharpness': 2 * left * right / max(left + right, 1e-6),
                         'meanLuma': float(gray.mean()), 'clippedFraction': float((gray >= 250).mean()),
                         **sample})
        i += 1
        if i % 900 == 0:
            print(f'profile: {i}/{len(pts)} frames', flush=True)
    cap.release()
    if i != len(pts):
        raise RuntimeError(f'Decoded {i}, expected {len(pts)} frames')
    write_json(output / 'profile.json', {'video': str(source), 'inputSha256': input_identity(source, pts_file),
                                       'decodedCount': i, 'samplingEveryNFrames': 3, 'frames': rows})
    print(f'profile: {len(rows)} candidates', flush=True)
    return rows


def select(rows, lap, bins):
    start = (lap - 1) * math.tau
    end = lap * math.tau
    candidates = [r for r in rows if start <= r['relativeYawRadians'] <= end]
    step = math.tau / bins
    median_pitch = float(np.median([r['pitchRadians'] for r in candidates]))
    chosen = []
    for b in range(bins):
        target = start + (math.tau if b == 0 else b * step)
        nearby = [r for r in candidates if abs(r['relativeYawRadians'] - target) < step * .42]
        if not nearby:
            raise RuntimeError(f'No image near lap {lap}, bin {b}')
        sharp = np.log1p([r['overlapSharpness'] for r in nearby])
        low, high = np.percentile(sharp, [10, 90])
        def cost(r):
            return (abs(r['relativeYawRadians'] - target) / step
                    + .20 * abs(r['angularSpeedRadiansPerSecond'])
                    + .5 * abs(r['pitchRadians'] - median_pitch)
                    - .15 * np.clip((math.log1p(r['overlapSharpness']) - low) / max(high-low, 1e-6), 0, 1))
        best = min(nearby, key=cost)
        chosen.append({**best, 'binIndex': b, 'lapIndex': lap - 1,
                       'relativeYawRadians': best['relativeYawRadians'] % math.tau,
                       'targetYawRadians': b * step, 'centerErrorRadians': abs(best['relativeYawRadians'] - target),
                       'sharpnessScore': best['sharpness'], 'qualityScore': -float(cost(best)),
                       'selectionSource': 'offline_angle_continuity_sharpness', 'filePath': f'video_{b:03}.jpg'})
    times = [r['ptsSeconds'] for r in chosen[1:] + chosen[:1]]
    if any(a >= b for a, b in zip(times, times[1:])):
        raise RuntimeError('Selection is not temporally ordered')
    return chosen


def prepare(source, pts_file, output):
    if output.resolve() == source.resolve() or source.resolve() in output.resolve().parents:
        raise ValueError('Use an output folder outside the original capture')
    output.mkdir(parents=True, exist_ok=True)
    profile_file = output / 'profile.json'
    if profile_file.exists():
        cached = json.loads(profile_file.read_text())
        if cached.get('inputSha256') != input_identity(source, pts_file):
            raise ValueError('Profile inputs differ or lack provenance; use a fresh output folder')
        rows = cached['frames']
    else:
        rows = profile(source, pts_file, output)
    targets = {}
    selected = {}
    for lap in (1, 2):
        for bins in (30, 60):
            folder = output / f'lap{lap}_{bins}'
            folder.mkdir(exist_ok=True)
            frames = select(rows, lap, bins)
            selected[str(folder)] = frames
            for frame in frames:
                targets.setdefault(frame['frameIndex'], []).append(folder / frame['filePath'])
            write_json(folder / 'timeline.json', {'frames': frames, 'selectedLap': lap, 'binCount': bins,
                                               'sourceVideo': str(source / 'giro360_capture.mp4')})
    cap = cv2.VideoCapture(str(source / 'giro360_capture.mp4'))
    cap.set(cv2.CAP_PROP_ORIENTATION_AUTO, 1)
    i = 0
    while cap.grab():
        if i in targets:
            ok, image = cap.retrieve()
            if not ok:
                raise RuntimeError(f'Cannot decode selected frame {i}')
            for target in targets.pop(i):
                if not cv2.imwrite(str(target), image, [cv2.IMWRITE_JPEG_QUALITY, 96]):
                    raise RuntimeError(f'Cannot write {target}')
        i += 1
        if not targets:
            break
    cap.release()
    if targets:
        raise RuntimeError(f'Missing {len(targets)} decoded frames')
    for folder, frames in selected.items():
        print(f'{Path(folder).name}: pitch span={np.ptp([math.degrees(f["pitchRadians"]) for f in frames]):.2f} deg; sharpness median={np.median([f["sharpness"] for f in frames]):.2f}', flush=True)


def native(timeline, library, output, mode):
    root = json.loads(timeline.read_text())
    frames = sorted(root['frames'], key=lambda f: f['binIndex'])
    names = [str(timeline.parent / Path(f['filePath']).name) for f in frames]
    if not all(Path(p).is_file() for p in names):
        raise RuntimeError('Missing source images')
    if output.resolve() in {Path(p).resolve() for p in names} | {timeline.resolve()}:
        raise ValueError('Output must not overwrite an input')
    output.parent.mkdir(parents=True, exist_ok=True)
    lib = ctypes.CDLL(str(library))
    fn = lib.giro360_stitch_with_options_v2
    fn.argtypes = [ctypes.POINTER(ctypes.c_char_p), ctypes.c_int, ctypes.POINTER(ctypes.c_double), ctypes.POINTER(ctypes.c_double), ctypes.c_int, ctypes.c_int, ctypes.c_char_p, ctypes.c_void_p, ctypes.c_int]
    fn.restype = ctypes.c_int
    paths = (ctypes.c_char_p * len(names))(*[p.encode() for p in names])
    yaws = (ctypes.c_double * len(names))(*[f['relativeYawRadians'] for f in frames])
    pitches = (ctypes.c_double * len(names))(*[f['pitchRadians'] for f in frames])
    buf = ctypes.create_string_buffer(65536)
    start = time.perf_counter()
    code = fn(paths, len(names), yaws, pitches, 0, mode, str(output).encode(), buf, len(buf))
    elapsed = time.perf_counter() - start
    status = buf.value.decode('utf-8')
    output.with_suffix('.txt').write_text(status)
    if code not in (0, 20) or not output.is_file():
        raise RuntimeError(f'Stitch failed with {code}: {status}')
    write_json(output.with_suffix('.json'), {'code': code, 'elapsedSeconds': elapsed, 'mode': mode,
                                          'sourceTimeline': str(timeline), 'library': str(library),
                                          'librarySha256': hashlib.sha256(library.read_bytes()).hexdigest()})
    print(f'{output.name}: code={code} time={elapsed:.2f}s', flush=True)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('command', choices=['prepare', 'native'])
    p.add_argument('--source', type=Path)
    p.add_argument('--pts', type=Path)
    p.add_argument('--timeline', type=Path)
    p.add_argument('--library', type=Path)
    p.add_argument('--output', required=True, type=Path)
    p.add_argument('--mode', default=4, type=int, choices=range(5))
    a = p.parse_args()
    required = ('source', 'pts') if a.command == 'prepare' else ('timeline', 'library')
    for name in required:
        if getattr(a, name) is None:
            p.error(f'--{name} is required for {a.command}')
    if a.command == 'prepare': prepare(a.source, a.pts, a.output)
    else: native(a.timeline, a.library, a.output, a.mode)

if __name__ == '__main__': main()
