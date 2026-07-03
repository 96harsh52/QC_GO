"""
OpenCV item counter for a FIXED background.

Counts items in a frame without any neural network:
  1. Subtract the empty background (or threshold directly if no background given)
  2. Clean up the mask (blur + morphology)
  3. Find contours and keep the ones large enough to be a real item
  4. Return one bounding box per item

The same logic is ported to Android (ItemCounter.kt). Keep the steps in sync.

Usage (single image):
    python count_opencv.py --image frame.jpg --background empty_bg.jpg --show

Usage (webcam, for quick tuning):
    python count_opencv.py --camera 0 --background empty_bg.jpg

Tip: capture `empty_bg.jpg` ONCE with nothing on the fixed background.
"""

import argparse
import cv2
import numpy as np


# --- tuning knobs (mirror these in ItemCounter.kt) ---------------------------
DEFAULTS = dict(
    blur_ksize=5,         # gaussian blur kernel (odd number); reduces sensor noise
    thresh=30,            # diff/binary threshold; raise if background noise leaks in
    min_area_frac=0.005,  # min contour area as fraction of frame area (drops specks)
    max_area_frac=0.95,   # max contour area as fraction of frame area (drops whole-frame blob)
    morph_ksize=5,        # morphological kernel to close holes / remove noise
)


def _to_gray_blur(img, blur_ksize):
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    if blur_ksize and blur_ksize >= 3:
        gray = cv2.GaussianBlur(gray, (blur_ksize, blur_ksize), 0)
    return gray


def build_mask(frame, background=None, cfg=DEFAULTS):
    """Return a binary mask where items are white (255), background black (0)."""
    gray = _to_gray_blur(frame, cfg["blur_ksize"])

    if background is not None:
        bg = _to_gray_blur(background, cfg["blur_ksize"])
        if bg.shape != gray.shape:
            bg = cv2.resize(bg, (gray.shape[1], gray.shape[0]))
        diff = cv2.absdiff(gray, bg)
        _, mask = cv2.threshold(diff, cfg["thresh"], 255, cv2.THRESH_BINARY)
    else:
        # No reference background: assume items differ from a uniform backdrop.
        # Otsu adapts the threshold automatically; invert so items become white.
        _, mask = cv2.threshold(
            gray, 0, 255, cv2.THRESH_BINARY_INV + cv2.THRESH_OTSU
        )

    # Close small holes inside items, then remove leftover specks.
    k = cv2.getStructuringElement(
        cv2.MORPH_ELLIPSE, (cfg["morph_ksize"], cfg["morph_ksize"])
    )
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, k, iterations=2)
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, k, iterations=1)
    return mask


def count_items(frame, background=None, cfg=DEFAULTS):
    """
    Count items in `frame`.

    Returns: (count, boxes) where boxes is a list of (x, y, w, h).
    """
    h, w = frame.shape[:2]
    frame_area = float(h * w)
    min_area = cfg["min_area_frac"] * frame_area
    max_area = cfg["max_area_frac"] * frame_area

    mask = build_mask(frame, background, cfg)
    contours, _ = cv2.findContours(
        mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE
    )

    boxes = []
    for c in contours:
        area = cv2.contourArea(c)
        if area < min_area or area > max_area:
            continue
        boxes.append(cv2.boundingRect(c))  # (x, y, w, h)

    # Sort left-to-right for stable, human-readable ordering.
    boxes.sort(key=lambda b: b[0])
    return len(boxes), boxes


def draw(frame, boxes):
    out = frame.copy()
    for (x, y, w, h) in boxes:
        cv2.rectangle(out, (x, y), (x + w, y + h), (0, 255, 0), 2)
    cv2.putText(
        out, f"count={len(boxes)}", (10, 30),
        cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 0, 255), 2,
    )
    return out


def crop(frame, box, pad=0.06):
    """Crop a box with a little padding, clamped to the frame. Use this crop to
    feed the clean/dirty classifier so train/serve preprocessing match."""
    h, w = frame.shape[:2]
    x, y, bw, bh = box
    px, py = int(bw * pad), int(bh * pad)
    x0, y0 = max(0, x - px), max(0, y - py)
    x1, y1 = min(w, x + bw + px), min(h, y + bh + py)
    return frame[y0:y1, x0:x1]


def _run_image(args, cfg):
    frame = cv2.imread(args.image)
    if frame is None:
        raise SystemExit(f"Could not read image: {args.image}")
    bg = cv2.imread(args.background) if args.background else None
    n, boxes = count_items(frame, bg, cfg)
    print(f"count = {n}")
    for i, b in enumerate(boxes):
        print(f"  item[{i}] box(x,y,w,h) = {b}")
    if args.show:
        cv2.imshow("count", draw(frame, boxes))
        cv2.waitKey(0)
        cv2.destroyAllWindows()


def _run_camera(args, cfg):
    bg = cv2.imread(args.background) if args.background else None
    cap = cv2.VideoCapture(args.camera)
    if not cap.isOpened():
        raise SystemExit(f"Could not open camera {args.camera}")
    print("Press 'q' to quit, 'b' to capture current frame as background.")
    while True:
        ok, frame = cap.read()
        if not ok:
            break
        n, boxes = count_items(frame, bg, cfg)
        cv2.imshow("count", draw(frame, boxes))
        key = cv2.waitKey(1) & 0xFF
        if key == ord("q"):
            break
        if key == ord("b"):
            bg = frame.copy()
            print("Captured background reference.")
    cap.release()
    cv2.destroyAllWindows()


def main():
    p = argparse.ArgumentParser(description="OpenCV item counter (fixed background)")
    src = p.add_mutually_exclusive_group(required=True)
    src.add_argument("--image", help="path to a single frame to count")
    src.add_argument("--camera", type=int, help="camera index for live counting")
    p.add_argument("--background", help="path to empty-background reference image")
    p.add_argument("--show", action="store_true", help="display result window")
    p.add_argument("--thresh", type=int, help="override diff/binary threshold")
    p.add_argument("--min-area-frac", type=float, dest="min_area_frac")
    args = p.parse_args()

    cfg = dict(DEFAULTS)
    if args.thresh is not None:
        cfg["thresh"] = args.thresh
    if args.min_area_frac is not None:
        cfg["min_area_frac"] = args.min_area_frac

    if args.image:
        _run_image(args, cfg)
    else:
        _run_camera(args, cfg)


if __name__ == "__main__":
    main()
