#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Regenerate independent fixtures from a locally supplied, hash-verified upstream file.

No network access and no dependency on App Matrix product implementations.
"""
import argparse
import hashlib
from pathlib import Path
import subprocess
import tempfile


def java_block(source, start):
    """Extract a balanced class/method block from this fixed upstream source."""
    opening = source.index("{", start)
    depth = 0
    for end in range(opening, len(source)):
        depth += source[end] == "{"
        depth -= source[end] == "}"
        if depth == 0:
            return source[start:end + 1]
    raise ValueError("Unbalanced upstream source")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Full pinned PhotoFilterView.java")
    parser.add_argument("--java-home", type=Path, required=True, help="JDK 17+ directory")
    parser.add_argument("--output", type=Path, required=True, help="Output fixture directory")
    args = parser.parse_args()
    data = args.source.read_bytes()
    blob_sha = hashlib.sha1(b"blob " + str(len(data)).encode("ascii") + b"\0" + data).hexdigest()
    if blob_sha != "0ad67a8ac554984c5217415e0898e7f7d9af650c":
        parser.error("Input is not the exact pinned Telegram blob: " + blob_sha)
    source = data.decode("utf-8")
    classes = []
    for name in ("CurvesValue", "CurvesToolValue"):
        block = java_block(source, source.index("    public static class " + name + " {"))
        for signature in ("        public void serializeToStream(", "        public void readParams("):
            block = block.replace(java_block(block, block.index(signature)), "")
        classes.append(block)
    copyright_notice = source[:source.index("package ")]
    oracle = (copyright_notice + "import java.nio.*;\nimport java.util.*;\n"
              + "public class PinnedTelegram {\n"
              + "private static final int curveGranularity=100,curveDataStep=2;\n"
              + "\n".join(classes) + "\n}\n")
    driver = Path(__file__).with_name("GenerateVectors.java.txt").read_text()
    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="telegram-curve-fixtures-") as temp:
        temp_path = Path(temp)
        (temp_path / "PinnedTelegram.java").write_text(oracle)
        (temp_path / "GenerateVectors.java").write_text(driver)
        subprocess.run([str(args.java_home / "bin/javac"), "--release", "17",
                        "PinnedTelegram.java", "GenerateVectors.java"], cwd=temp, check=True)
        for filename, extra in (("upstream-vectors.txt", []), ("cpu-vectors.txt", ["cpu"])):
            result = subprocess.run([str(args.java_home / "bin/java"), "-XX:-UsePerfData",
                                     "-cp", temp, "GenerateVectors", *extra],
                                    check=True, stdout=subprocess.PIPE)
            (args.output / filename).write_bytes(result.stdout)


if __name__ == "__main__":
    main()
