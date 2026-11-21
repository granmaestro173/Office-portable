#!/usr/bin/env python3
"""Small, asserted source patches applied to the editor before it is built.

Usage: patch-editor.py <editor-source-dir>
"""
import sys

root = sys.argv[1]


def patch(rel, old, new):
    path = f"{root}/{rel}"
    src = open(path, encoding="utf-8").read()
    if new in src:
        return
    if src.count(old) != 1:
        sys.exit(f"patch-editor: pattern not found exactly once in {rel}: {old!r}")
    open(path, "w", encoding="utf-8").write(src.replace(old, new))
    print(f"patched {rel}")


# The x2t converter worker holds ~300 MB+ (more for big files) and upstream keeps
# it alive for 30 s after every open and save. The Android app sets
# window.__OP_CONFIG.x2tIdleMs (1.5 s on low-RAM phones) so the memory is
# handed back almost immediately. Re-creating the worker costs well under a
# second per save.
patch(
    "lib/onlyoffice/guards/x2t-worker.ts",
    "const IDLE_TERMINATE_MS = 30_000;",
    "const IDLE_TERMINATE_MS: number =\n"
    "  (globalThis as { __OP_CONFIG?: { x2tIdleMs?: number } }).__OP_CONFIG?.x2tIdleMs ?? 30_000;",
)
