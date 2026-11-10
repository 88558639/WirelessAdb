#!/usr/bin/env python3
"""
从 docs/icon-src/launcher-source.jpg 生成 Android 自适应图标资源。

产物
  app/src/main/res/drawable/ic_launcher_background.xml        背景渐变（矢量，三段 135°）
  app/src/main/res/drawable-<dpi>/ic_launcher_foreground.png    前景：白色螺旋 + 投影（RGBA）
  app/src/main/res/mipmap-<dpi>/ic_launcher.png                legacy 方形图标
  app/src/main/res/mipmap-<dpi>/ic_launcher_round.png          legacy 圆形图标
  build/icon-preview-*.png                                     圆形遮罩下的效果预览

原理
  1. 源图是「白色陶瓷螺旋 + 绿→蓝对角渐变背景」。背景本身带暗角，
     用三次多项式拟合背景模型（拟合误差 MAE≈2），再按
     「像素亮度相对本地背景的亮度」求软边 alpha，螺旋边缘不锯齿。
  2. 投影（比背景模型暗、且靠近螺旋的部分）单独抽成半透明黑层，
     与螺旋做 source-over 烘进同一张前景 RGBA，
     保证投影随螺旋同步缩放、任何机型上都不会错位。
  3. 前景画布 108dp，图形按 CONTENT_SCALE 缩放居中；
     launcher 只显示中央 72dp 圆形，缩放比保证外圈白环不被裁掉。

用法
  python3 tools/gen_launcher_icon.py --preview-only --scale 0.76
  python3 tools/gen_launcher_icon.py
"""
import argparse
import math
import os

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, 'docs', 'icon-src', 'launcher-source.jpg')
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res')
BUILD = os.path.join(ROOT, 'build')

# 108dp 画布对应的各密度像素倍率
DENSITIES = [('mdpi', 1.0), ('hdpi', 1.5), ('xhdpi', 2.0), ('xxhdpi', 3.0), ('xxxhdpi', 4.0)]

CONTENT_SCALE = 0.76   # 图形在 108dp 画布中的占比
MASK_DIAMETER_DP = 72.0  # launcher 可见遮罩直径


def fit_background(a, core):
    """三次多项式拟合背景（绿→蓝对角渐变 + 暗角）。"""
    h, w, _ = a.shape
    ys, xs = np.nonzero(~core)
    nx = (xs / w) * 2 - 1
    ny = (ys / h) * 2 - 1

    def basis(x, y):
        return np.stack([np.ones_like(x), x, y, x * x, x * y, y * y,
                         x ** 3, x * x * y, x * y * y, y ** 3], axis=-1)

    coef, *_ = np.linalg.lstsq(basis(nx, ny), a[~core], rcond=None)
    gy, gx = np.mgrid[0:h, 0:w]
    model = np.clip(basis((gx / w) * 2 - 1, (gy / h) * 2 - 1) @ coef, 0, 255)
    return model


def gradient_colors(model):
    """从背景模型反推 135° 三段式线性渐变色。"""
    h, w, _ = model.shape
    yy, xx = np.mgrid[0:h, 0:w]
    t = (xx - w / 2) + (yy - h / 2)
    t = (t - t.min()) / (t.max() - t.min())
    cols = []
    for q in (0.0, 0.5, 1.0):
        sel = (t > max(0.0, q - 0.02)) & (t < min(1.0, q + 0.02))
        c = model[sel].mean(axis=0) if sel.sum() else np.zeros(3)
        cols.append('#%02X%02X%02X' % tuple(int(v) for v in c))
    return cols


def build_foreground(a):
    """分离螺旋 + 投影，返回前景 RGBA（源图分辨率）。"""
    mn = a.min(axis=2)
    core = mn > 130
    model = fit_background(a, core)
    mn_bg = model.min(axis=2)
    L = 0.299 * a[..., 0] + 0.587 * a[..., 1] + 0.114 * a[..., 2]

    # 螺旋 alpha：相对本地背景亮度的软过渡
    alpha = np.clip((mn - mn_bg - 4.0) / (215.0 - mn_bg - 4.0), 0, 1)
    alpha = np.where(core, 1.0, alpha) ** 0.85
    # 投影 alpha：比背景暗，且限制在螺旋邻域内
    shadow = np.clip((mn_bg - mn) / 46.0, 0, 1)
    prox = core.astype(np.float32)
    for _ in range(8):
        prox = (prox + np.roll(prox, 1, 0) + np.roll(prox, -1, 0)
                + np.roll(prox, 1, 1) + np.roll(prox, -1, 1)) / 5.0
    shadow = shadow * prox * 0.55

    # 前景颜色：保留陶瓷质感的明暗
    shade = np.clip((L - 150.0) / (250.0 - 150.0), 0, 1)[:, :, None]
    lo = np.array([198.0, 208.0, 206.0])
    hi = np.array([255.0, 255.0, 255.0])
    spiral_rgb = np.clip(lo + (hi - lo) * shade, 0, 255)

    # 螺旋 over 投影 → 烘成单张 RGBA
    a_s, a_b = alpha, shadow
    out_a = a_s + a_b * (1 - a_s)
    out_rgb = spiral_rgb * (a_s / np.maximum(out_a, 1e-6))[:, :, None]
    fg = np.dstack([out_rgb, out_a * 255]).astype(np.uint8)

    # monochrome 层（Android 13+ 主题图标）：只保留螺旋轮廓，投影去掉
    mono = np.dstack([np.full_like(out_rgb, 255), (alpha * 255)[:, :, None]]).astype(np.uint8)
    return fg, mono, model


def place_on_canvas(fg, scale, canvas_px):
    """把源图尺寸的前景等比缩放后居中放到 108dp 画布。"""
    new = int(round(canvas_px * scale))
    resized = Image.fromarray(fg).resize((new, new), Image.LANCZOS)
    canvas = Image.new('RGBA', (canvas_px, canvas_px), (0, 0, 0, 0))
    off = (canvas_px - new) // 2
    canvas.paste(resized, (off, off), resized)
    return canvas


def make_background_png(model, size):
    """把拟合出的背景模型缩放到目标尺寸（保留原图的暗角，不做额外裁切）。"""
    h, w, _ = model.shape
    return Image.fromarray(model.astype(np.uint8)).resize((size, size), Image.LANCZOS).convert('RGBA')


def write_background_xml(cols):
    """备份一份矢量渐变背景（供无 PNG 资源时回退，Android 12+ 主题取色用不到）。"""
    path = os.path.join(RES, 'drawable', 'ic_launcher_background.xml')
    xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
           '<shape xmlns:android="http://schemas.android.com/apk/res/android"\n'
           '    android:shape="rectangle">\n'
           '    <gradient\n'
           '        android:type="linear"\n'
           '        android:angle="315"\n'
           '        android:startColor="%s"\n'
           '        android:centerColor="%s"\n'
           '        android:endColor="%s" />\n'
           '</shape>\n') % tuple(cols)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(xml)


def write_adaptive_xml():
    """自适应图标 XML（官方模板布局：位图放 mipmap-*dpi）。"""
    for name in ('ic_launcher.xml', 'ic_launcher_round.xml'):
        path = os.path.join(RES, 'mipmap-anydpi-v26', name)
        xml = ('<?xml version="1.0" encoding="utf-8"?>\n'
               '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
               '    <background android:drawable="@mipmap/ic_launcher_background" />\n'
               '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
               '    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />\n'
               '</adaptive-icon>\n')
        with open(path, 'w', encoding='utf-8') as f:
            f.write(xml)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scale', type=float, default=CONTENT_SCALE)
    ap.add_argument('--preview-only', action='store_true')
    args = ap.parse_args()

    im = Image.open(SRC).convert('RGB')
    w, h = im.size
    side = min(w, h)
    im = im.crop(((w - side) // 2, (h - side) // 2,
                  (w - side) // 2 + side, (h - side) // 2 + side))
    a = np.asarray(im).astype(np.float32)
    print('源图: %dx%d' % im.size)

    fg, mono, model = build_foreground(a)
    cols = gradient_colors(model)
    print('背景渐变: %s -> %s -> %s' % tuple(cols))
    write_background_xml(cols)

    # ---- 预览：108dp 画布 + 72dp 圆形遮罩 ----
    canvas_px = 432
    fg_canvas = place_on_canvas(fg, args.scale, canvas_px)
    bg = make_background_png(model, canvas_px)
    comp = Image.alpha_composite(bg, fg_canvas)
    r = MASK_DIAMETER_DP / 108.0 * canvas_px / 2
    mask = Image.new('L', (canvas_px, canvas_px), 0)
    ImageDraw.Draw(mask).ellipse((canvas_px / 2 - r, canvas_px / 2 - r,
                                  canvas_px / 2 + r, canvas_px / 2 + r), fill=255)
    out = Image.new('RGBA', (canvas_px, canvas_px), (0, 0, 0, 0))
    out.paste(comp, (0, 0), mask)
    os.makedirs(BUILD, exist_ok=True)
    tag = 'scale%.2f' % args.scale
    out.save(os.path.join(BUILD, 'icon-preview-%s.png' % tag))
    comp.convert('RGB').save(os.path.join(BUILD, 'icon-full-%s.png' % tag))
    print('预览: build/icon-preview-%s.png' % tag)

    if args.preview_only:
        return

    # ---- 写入正式资源（官方模板布局：位图放 mipmap-*dpi）----
    for name, mult in DENSITIES:
        mp = os.path.join(RES, 'mipmap-' + name)
        os.makedirs(mp, exist_ok=True)

        # 自适应图标层：108dp 画布
        px = int(round(108 * mult))
        make_background_png(model, px).save(os.path.join(mp, 'ic_launcher_background.png'))
        place_on_canvas(fg, args.scale, px).save(os.path.join(mp, 'ic_launcher_foreground.png'))
        place_on_canvas(mono, args.scale, px).save(os.path.join(mp, 'ic_launcher_monochrome.png'))

        # legacy 图标：48dp 画布
        lpx = int(round(48 * mult))
        legacy_fg = place_on_canvas(fg, args.scale, lpx)
        full = Image.alpha_composite(make_background_png(model, lpx), legacy_fg)

        sqmask = Image.new('L', (lpx, lpx), 0)
        ImageDraw.Draw(sqmask).rounded_rectangle((0, 0, lpx - 1, lpx - 1),
                                                 radius=int(lpx * 0.22), fill=255)
        sq = Image.new('RGBA', (lpx, lpx), (0, 0, 0, 0))
        sq.paste(full, (0, 0), sqmask)
        sq.save(os.path.join(mp, 'ic_launcher.png'))

        rndmask = Image.new('L', (lpx, lpx), 0)
        ImageDraw.Draw(rndmask).ellipse((0, 0, lpx - 1, lpx - 1), fill=255)
        rnd = Image.new('RGBA', (lpx, lpx), (0, 0, 0, 0))
        rnd.paste(full, (0, 0), rndmask)
        rnd.save(os.path.join(mp, 'ic_launcher_round.png'))
        print('写入 mipmap-%s/：background+foreground(%dpx) / ic_launcher+round(%dpx)'
              % (name, px, lpx))

    write_adaptive_xml()
    print('写入 mipmap-anydpi-v26/ic_launcher.xml 与 ic_launcher_round.xml')


if __name__ == '__main__':
    main()
