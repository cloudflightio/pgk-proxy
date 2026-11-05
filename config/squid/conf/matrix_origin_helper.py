#!/usr/bin/env python3
"""
Squid external ACL helper: allow only if the Matrix federation
Authorization header's origin= is in a dstdomain-style whitelist.
The whitelist is read once at startup; Squid restarts helpers on reconfigure.

Input  (per line): <url-escaped Authorization header value, or "-">
Output (per line): OK|ERR log=<reason>
"""


#  Copyright 2026 MA 01 - Wien Digital
#
#   Licensed under the Apache License, Version 2.0 (the "License");
#   you may not use this file except in compliance with the License.
#   You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#   Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#   See the License for the specific language governing permissions and
#   limitations under the License.

import re
import sys
from urllib.parse import unquote

WHITELIST = "/etc/squid/dynamic/whitelist.txt"

SCHEME = re.compile(r"^\s*X-Matrix\s+(.*)$", re.I | re.S)
PARAM = re.compile(r'([A-Za-z_][A-Za-z0-9_-]*)\s*=\s*("(?:[^"\\]|\\.)*"|[^,\s"]*)')
HOST_OK = re.compile(r"^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$")


def load_whitelist(path):
    """dstdomain semantics: 'example.org' = exact, '.example.org' = domain + subdomains."""
    exact, suffix = set(), set()
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                entry = line.split("#", 1)[0].strip().lower().rstrip(".")
                if not entry:
                    continue
                if entry.startswith("."):
                    suffix.add(entry[1:])
                else:
                    exact.add(entry)
    except OSError as e:
        # Fail closed: empty list denies everything, but say why
        print(f"whitelist unreadable: {e}", file=sys.stderr, flush=True)
    print(f"whitelist loaded: {len(exact)} exact, {len(suffix)} suffix entries",
          file=sys.stderr, flush=True)
    return exact, suffix


def allows(host, exact, suffix):
    if host in exact or host in suffix:
        return True
    labels = host.split(".")
    return any(".".join(labels[i:]) in suffix for i in range(1, len(labels)))


def extract_origin(header):
    """Return (origin_host, None) or (None, reason)."""
    if not header or header == "-":
        return None, "no-auth-header"
    m = SCHEME.match(header)
    if not m:
        return None, "not-x-matrix"
    origins = [v for k, v in PARAM.findall(m.group(1)) if k.lower() == "origin"]
    if len(origins) != 1:  # none, or duplicated/smuggled
        return None, "origin-count-%d" % len(origins)
    origin = origins[0].strip('"').strip().lower()
    if origin.startswith("["):
        return None, "ip-literal"
    host, sep, port = origin.rpartition(":")
    if sep and port.isdigit():
        origin = host
    origin = origin.rstrip(".")
    if not HOST_OK.match(origin):
        return None, "bad-origin"
    return origin, None


def main():
    exact, suffix = load_whitelist(WHITELIST)
    for line in sys.stdin:
        try:
            origin, err = extract_origin(unquote(line.rstrip("\r\n")))
            if err:
                reply = "ERR log=%s" % err
            elif allows(origin, exact, suffix):
                reply = "OK log=allowed:%s" % origin
            else:
                reply = "ERR log=denied:%s" % origin
        except Exception as e:  # always fail closed
            print(f"helper error: {e!r}", file=sys.stderr, flush=True)
            reply = "ERR log=helper-error"
        print(reply, flush=True)


if __name__ == "__main__":
    main()
