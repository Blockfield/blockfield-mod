"""equirect2cube.py <equirect.png> <outdir> [face_px]
Раскладка граней как у ванильного CubeMap 1.21.1: 0 перед, 1 право, 2 зад, 3 лево, 4 верх, 5 низ;
камера смотрит в -Z, +X вправо, +Y вверх (выведено из CubeMap.render: R_x(180)·R_face·квад z=+1)."""

import sys
import numpy as np
from PIL import Image

src, out = sys.argv[1], sys.argv[2]
N = int(sys.argv[3]) if len(sys.argv) > 3 else 1024
SS = 2  # суперсэмплинг, потом LANCZOS вниз
img = np.asarray(Image.open(src).convert("RGB"))
H, W, _ = img.shape
faces = {  # центр, направление "вправо по картинке", направление "вниз по картинке"
    0: ((0, 0, -1), (1, 0, 0), (0, -1, 0)),
    1: ((1, 0, 0), (0, 0, 1), (0, -1, 0)),
    2: ((0, 0, 1), (-1, 0, 0), (0, -1, 0)),
    3: ((-1, 0, 0), (0, 0, -1), (0, -1, 0)),
    4: ((0, 1, 0), (1, 0, 0), (0, 0, -1)),
    5: ((0, -1, 0), (1, 0, 0), (0, 0, 1)),
}
n = N * SS
s = (np.arange(n) + 0.5) / n * 2 - 1
S, T = np.meshgrid(s, s)  # S вправо, T вниз
for i, (C, U, V) in faces.items():
    C, U, V = (np.array(a, dtype=np.float64) for a in (C, U, V))
    P = C[None, None, :] + S[..., None] * U + T[..., None] * V
    P /= np.linalg.norm(P, axis=-1, keepdims=True)
    x, y, z = P[..., 0], P[..., 1], P[..., 2]
    phi = np.arctan2(x, -z)  # 0 = вперёд, растёт вправо
    theta = np.arcsin(np.clip(y, -1, 1))
    u = (0.5 + phi / (2 * np.pi)) % 1.0
    v = 0.5 - theta / np.pi
    px = np.clip((u * W).astype(int), 0, W - 1)
    py = np.clip((v * H).astype(int), 0, H - 1)
    face = Image.fromarray(img[py, px]).resize((N, N), Image.LANCZOS)
    face.save(f"{out}/panorama_{i}.png")
    print("face", i, face.size)
