"""COOKBOOK.md recipe 35: verify a journal written by Java's JournalWriter from Python."""

import json
import struct
from pathlib import Path

from ratesresearch.fnv import fnv1a64, to_java_long

root = Path(__file__).resolve().parents[2]

# 1. the shared known answers that rates-journal ChainHashTest also checks
vectors = json.loads((root / "data/golden/chain_hash_vectors.json").read_text(encoding="utf-8"))
for v in vectors["vectors"]:
    got = fnv1a64(v["payload_ascii"].encode("ascii"), int(v["previous"], 16))
    verdict = "ok" if got == int(v["hash"], 16) else "MISMATCH"
    print(f"vector previous={v['previous']} payload={v['payload_ascii']!r}: {got:#018x} {verdict}")


# 2. walk the frames of a file JournalWriter wrote (pinned by rates-cookbook JournalFixtureTest)
def walk(data: bytes) -> tuple[int, int]:
    """Return (frames verified, final hash); stop at the first broken link."""
    pos, prev, count = 32, 0, 0
    while pos + 4 <= len(data):
        (length,) = struct.unpack_from("<i", data, pos)
        if length == 0:
            break
        seq, ts, template, _version, stored = struct.unpack_from("<qqHHq", data, pos + 4)
        payload = data[pos + 32 : pos + length]
        h = fnv1a64(payload, prev)
        if to_java_long(h) != stored:
            stored_hex = f"{stored & (2**64 - 1):#018x}"
            print(f"  chain broken at sequence {seq}: stored {stored_hex}, computed {h:#018x}")
            return count, prev
        (value,) = struct.unpack_from("<q", payload)
        print(f"  seq={seq} ts={ts} template={template} value={value} hash={h:016x}")
        prev, count = h, count + 1
        pos += (length + 7) & ~7
    return count, prev


data = (root / "data/fixtures/journal/recipe02.raj").read_bytes()
magic, version, header, capacity = struct.unpack_from("<4sHHi", data, 0)
print(f"journal {magic.decode()} version {version} header {header} bytes capacity {capacity}")
frames, final = walk(data)

# 3. the final hash Java printed in recipe 2's recorded output
java_out = root / "rates-cookbook/src/test/resources/expected/recipe02.txt"
words = java_out.read_text(encoding="utf-8").split()
java_final = next(w for w in words if w.startswith("finalHash=")).split("=")[1]
match = f"{final:x}" == java_final
print(f"frames={frames} finalHash={final:x} java finalHash={java_final} match={match}")

# 4. flip one payload byte of the second frame, as recipe 2 does
tampered = bytearray(data)
tampered[32 + 40 + 32] ^= 0x01
print("after flipping one byte of frame 2:")
frames, _ = walk(bytes(tampered))
print(f"frames verified before the break: {frames}")
