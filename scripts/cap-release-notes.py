#!/usr/bin/env python3
"""Cap an extracted release-notes file at GitHub's release-body limit.

GitHub rejects a release whose body exceeds 125,000 characters with an HTTP 422, and `gh release
create` fails the whole call on it. That failure mode is worse than it sounds: an over-long section
produces **no Release at all**, not even the pointer fallback `.github/workflows/release.yml`
already has for a *missing* section. The two failures deserve the same treatment -- publish
something and say where the rest is.

This is not hypothetical. In aimon-core, whose file of the same name this is a verbatim port of,
`[0.2.4]` consolidated four releases' worth of entries and extracted to 156,902 characters, 32k
over. This repository's first release consolidates every step since the split into one section.

Runs *after* `absolutize-release-links.py`, never before: absolutization only ever grows the text,
so capping first would let it cross back over the limit.

Truncation is on a line boundary, and an unbalanced code fence left by the cut is closed, so the
body stays valid Markdown rather than rendering the footer inside a code block.

Usage:
    cap-release-notes.py <file> <limit> <changelog-url>
"""

import io
import sys

_FENCE = "```"


def cap(text, limit, changelog_url):
    """Return `text` shortened to at most `limit` characters, with a pointer appended.

    Returns the text unchanged when it already fits.
    """
    if len(text) <= limit:
        return text

    footer = (
        "\n\n---\n\n"
        "*These notes were truncated to fit GitHub's release-body limit. "
        "The full section is in [CHANGELOG.md](%s).*\n" % changelog_url
    )
    # Reserve room for the footer, and for the fence-closing line it may need.
    budget = limit - len(footer) - len(_FENCE) - 1
    if budget <= 0:
        raise SystemExit("cap-release-notes: limit %d is too small for the pointer footer" % limit)

    head = text[:budget]
    # Cut on a line boundary so the body never ends mid-sentence inside a list item or table row.
    boundary = head.rfind("\n")
    if boundary > 0:
        head = head[:boundary]

    # An odd number of fence lines means the cut landed inside a code block.
    fences = sum(1 for line in head.splitlines() if line.lstrip().startswith(_FENCE))
    if fences % 2 == 1:
        head += "\n" + _FENCE

    return head + footer


def main(argv):
    if len(argv) != 4:
        raise SystemExit(__doc__.strip().splitlines()[-1].strip())

    path, limit, changelog_url = argv[1], int(argv[2]), argv[3]

    with io.open(path, encoding="utf-8") as handle:
        text = handle.read()

    capped = cap(text, limit, changelog_url)
    if capped is text:
        return 0

    with io.open(path, "w", encoding="utf-8") as handle:
        handle.write(capped)

    print(
        "capped release notes: %d chars -> %d (limit %d); pointed at %s"
        % (len(text), len(capped), limit, changelog_url)
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
