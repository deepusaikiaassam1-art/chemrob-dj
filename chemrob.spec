# -*- mode: python ; coding: utf-8 -*-
"""
PyInstaller spec for ChemRob DJ.

RDKit, scikit-learn and scipy all break under a naive PyInstaller build, each
for a different reason, so none of the collection below is boilerplate:

  * RDKit loads compiled extension modules dynamically and reads data files at
    import time (SMARTS definition files, the SA_Score contrib script). Module
    analysis cannot see any of that, so `collect_all` is required or the exe
    builds cleanly and then fails the moment a molecule is parsed.
  * scikit-learn and scipy resolve parts of themselves at runtime; without
    their hidden imports, unpickling a model raises ModuleNotFoundError.
  * uvicorn picks its event loop and protocol implementations by name at
    startup, so those have to be named explicitly too.

Built as a **one-folder** app rather than one-file: a one-file build re-extracts
several hundred megabytes to a temp directory on every launch, which with RDKit
means a 20-30 second wait each time. One-folder starts immediately and zips just
as well for sending to someone.
"""
from PyInstaller.utils.hooks import collect_all, collect_submodules

datas, binaries, hiddenimports = [], [], []

# Packages that need their data files and dynamic extensions collected whole.
for pkg in ("rdkit", "sklearn", "scipy", "pandas", "pyarrow", "joblib"):
    d, b, h = collect_all(pkg)
    datas += d
    binaries += b
    hiddenimports += h

# Runtime-resolved imports the analyser cannot follow.
hiddenimports += collect_submodules("sklearn.utils")
hiddenimports += collect_submodules("sklearn.ensemble")
hiddenimports += collect_submodules("sklearn.calibration")
hiddenimports += [
    "sklearn.frozen",
    "scipy.special._cdflib",
    "uvicorn.logging",
    "uvicorn.loops.auto",
    "uvicorn.loops.asyncio",
    "uvicorn.protocols.http.auto",
    "uvicorn.protocols.http.h11_impl",
    "uvicorn.protocols.websockets.auto",
    "uvicorn.lifespan.on",
    "chemrob.api",
]

# The workbench page is loaded from disk at request time.
datas += [("chemrob/static/app.html", "chemrob/static")]

a = Analysis(
    ["chemrob_launcher.py"],
    pathex=["."],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    runtime_hooks=[],
    # torch is only used by the optional D-MPNN backend and would add ~800 MB.
    excludes=["torch", "matplotlib", "tkinter", "IPython", "notebook", "pytest"],
    noarchive=False,
)

pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="ChemRobDJ",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name="ChemRobDJ",
)
