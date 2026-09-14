"""
Hold the machine awake for the duration of a long run.

A seed-variance run was killed 21 minutes in with no traceback. The cause was
not the script and not memory: Kernel-Power logged "the system is entering
sleep" fourteen seconds after the last line the run wrote. Windows' idle timer
does not count CPU load as activity, so an hour-long fit with no keyboard input
looks idle, and on battery this machine suspends after three minutes.

SetThreadExecutionState tells the power manager the calling thread has work in
progress. ES_CONTINUOUS makes the request stick until it is cleared, and
ES_SYSTEM_REQUIRED covers system sleep. ES_AWAYMODE_REQUIRED is deliberately not
used: it keeps the machine running with the screen off, which is a stronger
claim than a batch job should make on someone's laptop.

The request is per-thread and dies with the process, so a crashed run cannot
leave the machine unable to sleep.

    with keep_awake("seed variance"):
        ...

Everything here is a no-op off Windows and on any failure to load the API, so
callers never need to guard the import.
"""
from __future__ import annotations

import contextlib
import logging
import sys
from collections.abc import Iterator

log = logging.getLogger(__name__)

ES_CONTINUOUS = 0x80000000
ES_SYSTEM_REQUIRED = 0x00000001
ES_DISPLAY_REQUIRED = 0x00000002


def _set_state(flags: int) -> bool:
    if not sys.platform.startswith("win"):
        return False
    try:
        import ctypes

        # Returns the previous state, or 0 on failure.
        return bool(ctypes.windll.kernel32.SetThreadExecutionState(ctypes.c_uint(flags)))
    except Exception as exc:                                  # pragma: no cover
        log.debug("could not set execution state: %s", exc)
        return False


@contextlib.contextmanager
def keep_awake(reason: str = "long-running job", *,
               keep_display: bool = False) -> Iterator[bool]:
    """
    Ask Windows not to sleep while the block runs; yield whether it was granted.

    `keep_display` also holds the screen on. Leave it off for batch work - the
    display blanking is welcome, it is the suspend that kills the run.
    """
    flags = ES_CONTINUOUS | ES_SYSTEM_REQUIRED
    if keep_display:
        flags |= ES_DISPLAY_REQUIRED
    held = _set_state(flags)
    if held:
        log.info("holding the system awake for %s "
                 "(display may still blank; press Ctrl-C to stop the run)", reason)
    elif sys.platform.startswith("win"):
        log.warning("could not hold the system awake; a sleep will kill this run")
    try:
        yield held
    finally:
        if held:
            _set_state(ES_CONTINUOUS)
            log.debug("released the wake lock")
