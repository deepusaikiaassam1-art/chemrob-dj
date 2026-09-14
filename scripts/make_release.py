"""
Build a self-contained ChemRob DJ bundle to hand to someone else.

    python scripts/make_release.py

Produces `dist/ChemRobDJ-<version>.zip` containing the code, the trained model
and a Windows installer - everything needed to run predictions on a machine
that has never seen this project.

What is deliberately left out is the 2.2 GB `data/` directory. A recipient does
not need it: it holds the raw ChEMBL download and the feature cache, both of
which exist only to *train* the model, and both of which regenerate from
`build_dataset.py`. Including it would turn a 50 MB attachment into an
undeliverable one. Pass --with-data if the recipient really is going to retrain.
"""
from __future__ import annotations

import argparse
import shutil
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from chemrob import __version__

ROOT = Path(__file__).resolve().parents[1]

# Files and folders the recipient needs.
INCLUDE_FILES = [
    "pyproject.toml", "requirements.txt", "README.md",
    "install.bat", "start-chemrob.bat", "chemrob.bat", "uninstall.bat",
    "demo_library.csv",
]
INCLUDE_DIRS = ["chemrob", "scripts"]

# Never ship these, whatever directory they appear in.
EXCLUDE_PARTS = {"__pycache__", ".venv", ".git", "dist", "chembl_cache"}
EXCLUDE_SUFFIX = {".log", ".pyc", ".pyo", ".partial", ".f32"}

SETUP_TEXT = """ChemRob DJ {version}
=========================================================

Structure-based pharmacological activity prediction.

WHAT THIS IS
    Paste or draw a chemical structure and get predicted activity across 14
    pharmacological classes, estimated potency, target selectivity, the
    functional groups driving the prediction, and precedented suggestions for
    what to change next.

    The trained model is included. Nothing needs downloading to use it.


INSTALL  (Windows)
    1. Unzip this folder anywhere you like.
    2. Double-click  install.bat
       It builds a private Python environment inside this folder and installs
       everything it needs. Nothing is written elsewhere on the machine.
       First run takes a few minutes.

    You need Python 3.10 or newer. If install.bat says it cannot find Python,
    get it from https://www.python.org/downloads/ and tick
    "Add python.exe to PATH" during setup.


USE IT
    Double-click  start-chemrob.bat
        Opens the workbench at http://127.0.0.1:8000/app
        Draw or paste a structure, press Predict. Close the window to stop.

    Or from a terminal in this folder:
        chemrob.bat predict --smiles "CC(=O)Oc1ccccc1C(=O)O"
        chemrob.bat screen demo_library.csv
        chemrob.bat diseases leishmaniasis
        chemrob.bat                          (welcome banner and main commands)


REMOVE IT
    Double-click  uninstall.bat  - deletes the private environment only.
    Delete the folder to remove everything.


BEFORE YOU TRUST A NUMBER
    Read the applicability-domain line in every report. "out_of_domain" means
    the molecule is outside the chemistry the model learned from, and the
    probabilities below it are guesses.

    Held-out accuracy is macro AUPRC 0.905 on unseen scaffolds, but 0.709 on a
    prospective time split - the second number is the realistic one for
    genuinely new chemistry.

    Predictions are hypotheses for prioritising what to make and assay first.
    Not for clinical or regulatory use.

    Full documentation is in README.md.

Bioactivity data from ChEMBL (EMBL-EBI), CC BY-SA 3.0.
"""


def _skip(path: Path) -> bool:
    if any(part in EXCLUDE_PARTS for part in path.parts):
        return True
    return path.suffix.lower() in EXCLUDE_SUFFIX


def collect(with_data: bool, with_model: bool) -> list[tuple[Path, str]]:
    """Return (absolute source, archive name) pairs."""
    items: list[tuple[Path, str]] = []

    for name in INCLUDE_FILES:
        p = ROOT / name
        if p.exists():
            items.append((p, name))

    for d in INCLUDE_DIRS:
        for p in sorted((ROOT / d).rglob("*")):
            if p.is_file() and not _skip(p.relative_to(ROOT)):
                items.append((p, str(p.relative_to(ROOT)).replace("\\", "/")))

    if with_model:
        for p in sorted((ROOT / "artifacts").rglob("*")):
            if p.is_file() and not _skip(p.relative_to(ROOT)):
                items.append((p, str(p.relative_to(ROOT)).replace("\\", "/")))

    if with_data:
        for p in sorted((ROOT / "data").rglob("*")):
            if p.is_file() and not _skip(p.relative_to(ROOT)):
                items.append((p, str(p.relative_to(ROOT)).replace("\\", "/")))

    return items


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--with-data", action="store_true",
                    help="also include data/ (adds ~1 GB; only needed to retrain)")
    ap.add_argument("--no-model", action="store_true",
                    help="ship code only; the recipient would have to train")
    ap.add_argument("--out", default=None, help="output .zip path")
    args = ap.parse_args()

    if not (ROOT / "artifacts" / "baseline_activity.joblib").exists() and not args.no_model:
        print("No trained model in artifacts/ - run scripts/train.py first, "
              "or pass --no-model.", file=sys.stderr)
        return 1

    items = collect(args.with_data, not args.no_model)
    if not items:
        print("Nothing to package.", file=sys.stderr)
        return 1

    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    suffix = "-with-data" if args.with_data else ("-code-only" if args.no_model else "")
    out = Path(args.out) if args.out else dist / f"ChemRobDJ-{__version__}{suffix}.zip"
    stem = f"ChemRobDJ-{__version__}"

    raw = sum(p.stat().st_size for p, _ in items)
    print(f"Packaging {len(items)} files ({raw / 1e6:.1f} MB uncompressed) ...")

    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        z.writestr(f"{stem}/SETUP.txt", SETUP_TEXT.format(version=__version__))
        for src, arc in items:
            z.write(src, f"{stem}/{arc}")

    size = out.stat().st_size
    print(f"\nWrote {out}")
    print(f"  {size / 1e6:.1f} MB compressed  ({raw / max(size, 1):.1f}x)")
    print(f"  unzips to {stem}/ with SETUP.txt at the top")
    if not args.with_data:
        print("\n  data/ was excluded - the recipient can predict immediately but")
        print("  would need `build_dataset.py` to retrain. Use --with-data to include it.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
