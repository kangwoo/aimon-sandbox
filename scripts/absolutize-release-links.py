#!/usr/bin/env python3
"""Rewrite in-repo relative links in an extracted release-notes file to absolute URLs.

A GitHub Release body is not rendered from a path inside the repository, so a relative link in it
resolves against the release page itself and 404s. `.github/workflows/release.yml` already knew
this for its fallback pointer; this applies the same rule to the body it cuts out of CHANGELOG.md.

The changelog keeps writing its links relative on purpose -- that is what renders on GitHub -- so
the rewrite happens here, at the single point where the text leaves the repository.

A verbatim port of aimon-core's file of the same name, less this paragraph's references to core's
link checker and docs site, which this repository does not have.

Usage:
    absolutize-release-links.py <file> <server-url/owner/repo> <tag>
"""

import re
import sys

# `](#anchor)` is a link into the section of CHANGELOG.md the notes were cut from.
_FRAGMENT = re.compile(r"\]\(#([^)\s]+)\)")

# Any other in-repo path. Anything already absolute, a mail link, or a protocol-relative URL is
# left alone; so is an empty target, which is not a link worth rewriting.
_RELATIVE = re.compile(r"\]\((?!https?://|mailto:|//|#|\))([^)\s]+)(\s+\"[^\"]*\")?\)")


def absolutize(text: str, blob_base: str) -> str:
    text = _FRAGMENT.sub(rf"]({blob_base}/CHANGELOG.md#\1)", text)
    return _RELATIVE.sub(rf"]({blob_base}/\1\2)", text)


def main(argv: list[str]) -> int:
    if len(argv) != 4:
        print(__doc__, file=sys.stderr)
        return 2

    path, repo_url, tag = argv[1], argv[2].rstrip("/"), argv[3]
    blob_base = f"{repo_url}/blob/{tag}"

    with open(path, encoding="utf-8") as handle:
        original = handle.read()

    rewritten = absolutize(original, blob_base)

    with open(path, "w", encoding="utf-8") as handle:
        handle.write(rewritten)

    changed = sum(1 for a, b in zip(original.splitlines(), rewritten.splitlines()) if a != b)
    print(f"absolutized links on {changed} line(s) against {blob_base}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
