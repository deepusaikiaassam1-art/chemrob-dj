"""
Backwards-compatibility shim for the rename to `chemrob` (ChemRob DJ).

This covers two different callers:

  * People and scripts that still type `chemactivity` - they get a warning and
    the new package.
  * **joblib/pickle loading old artifacts.** Model bundles saved before the
    rename record their classes as `chemactivity.models.baseline.…`, and pickle
    resolves that by importing the module path literally. Without a mapping,
    every previously-trained bundle fails to load with ModuleNotFoundError.

A meta-path finder handles the second case generically, so submodules added
later are covered without touching this file.
"""
from __future__ import annotations

import importlib
import importlib.abc
import importlib.machinery
import sys
import warnings

_OLD = "chemactivity"
_NEW = "chemrob"


class _RenameFinder(importlib.abc.MetaPathFinder, importlib.abc.Loader):
    """Resolves any `chemactivity.<path>` import to `chemrob.<path>`."""

    def find_spec(self, fullname, path=None, target=None):
        if not fullname.startswith(_OLD + "."):
            return None
        # Let the real files in this shim package win (e.g. chemactivity.cli).
        if fullname in sys.modules:
            return None
        return importlib.machinery.ModuleSpec(fullname, self)

    def create_module(self, spec):
        new_name = _NEW + spec.name[len(_OLD):]
        module = importlib.import_module(new_name)
        sys.modules[spec.name] = module
        return module

    def exec_module(self, module):  # already executed as the chemrob module
        return None


if not any(isinstance(f, _RenameFinder) for f in sys.meta_path):
    sys.meta_path.append(_RenameFinder())

warnings.warn(
    "`chemactivity` has been renamed to `chemrob`; "
    "use `python -m chemrob.cli` instead.",
    DeprecationWarning,
    stacklevel=2,
)

from chemrob import APP_NAME, __version__  # noqa: E402,F401
