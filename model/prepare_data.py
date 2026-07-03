"""
Reshape Model_data/{clean,dirt} into the train/val layout the trainer expects:

    data/train/clean  data/train/dirty
    data/val/clean    data/val/dirty

- Maps the source folder 'dirt' -> class 'dirty' (script/app class name).
- Deterministic 90/10 split (seed fixed) via symlinks (no image copies, saves disk).
Usage:
    python prepare_data.py --src ../Model_data --out ./data --val-frac 0.1
"""
import argparse
import os
import random

SRC_TO_CLASS = {"clean": "clean", "dirt": "dirty", "dirty": "dirty"}
EXTS = (".jpg", ".jpeg", ".png", ".bmp", ".webp")


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--src", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--val-frac", type=float, default=0.1)
    p.add_argument("--seed", type=int, default=42)
    args = p.parse_args()

    src = os.path.abspath(args.src)
    out = os.path.abspath(args.out)
    rng = random.Random(args.seed)

    counts = {}
    for src_name, cls in SRC_TO_CLASS.items():
        d = os.path.join(src, src_name)
        if not os.path.isdir(d):
            continue
        files = [f for f in os.listdir(d) if f.lower().endswith(EXTS)]
        files.sort()
        rng.shuffle(files)
        n_val = int(len(files) * args.val_frac)
        splits = {"val": files[:n_val], "train": files[n_val:]}
        for split, flist in splits.items():
            dst_dir = os.path.join(out, split, cls)
            os.makedirs(dst_dir, exist_ok=True)
            for f in flist:
                link = os.path.join(dst_dir, f)
                if os.path.lexists(link):
                    os.remove(link)
                os.symlink(os.path.join(d, f), link)
            counts[f"{split}/{cls}"] = counts.get(f"{split}/{cls}", 0) + len(flist)

    print("Prepared dataset at", out)
    for k in sorted(counts):
        print(f"  {k}: {counts[k]}")


if __name__ == "__main__":
    main()
