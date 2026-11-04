"""Offline previews: `preview.py <asset> [out-dir]` writes per-variant views and a turntable."""

import importlib
from pathlib import Path
import subprocess
import sys

import numpy as np
from PIL import Image

from kit import render, save_png

HERE = Path(__file__).resolve().parent


def fit(img, mask, size):
    """Crop to the silhouette with a margin and scale into a square tile."""
    ys, xs = np.nonzero(mask > 0.02)
    side = max(np.ptp(ys), np.ptp(xs)) * 1.18 + 8
    cy, cx = (ys.min() + ys.max()) / 2, (xs.min() + xs.max()) / 2
    box = (
        int(cx - side / 2),
        int(cy - side / 2),
        int(cx + side / 2),
        int(cy + side / 2),
    )
    pil = Image.fromarray((img * 255).astype(np.uint8))
    return np.asarray(pil.crop(box).resize((size, size), Image.LANCZOS)) / 255


def views(model, atlas, uv, size=384, angles=((30, 24), (-35, 18), (150, 28))):
    shots = [render(model, atlas, uv, yaw=y, pitch=p, size=size * 2) for y, p in angles]
    return np.concatenate([fit(img, mask, size) for img, mask in shots], axis=1)


def turntable(model, atlas, uv, out, frames=72, size=480):
    out.mkdir(parents=True, exist_ok=True)
    for i in range(frames):
        img, _ = render(
            model, atlas, uv, yaw=30 + i * 360 / frames, pitch=22, size=size
        )
        save_png(img, out / f"{i:03d}.png")
    subprocess.run(
        [
            "ffmpeg",
            "-loglevel",
            "error",
            "-y",
            "-framerate",
            "30",
            "-i",
            str(out / "%03d.png"),
            "-vf",
            "format=yuv420p",
            "-c:v",
            "libx264",
            "-crf",
            "18",
            str(out.with_suffix(".mp4")),
        ],
        check=True,
    )


def main():
    asset = sys.argv[1]
    out = Path(sys.argv[2]) if len(sys.argv) > 2 else HERE / "out"
    out.mkdir(parents=True, exist_ok=True)
    module = importlib.import_module(asset)
    for key, (_, build) in module.VARIANTS.items():
        model = build()
        atlas, uv = model.bake()
        save_png(atlas[..., :3], out / f"{asset}_{key}_atlas.png", atlas[..., 3])
        save_png(views(model, atlas, uv), out / f"{asset}_{key}.png")
        if "--turntable" in sys.argv:
            turntable(model, atlas, uv, out / f"{asset}_{key}_turn")


if __name__ == "__main__":
    main()
