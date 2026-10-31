#!/usr/bin/env bash
# The SVG is parsed, not eyeballed: point coordinates, axis labels and the legend are asserted.
set -uo pipefail
cd "$(dirname "$0")/.."
./build.sh >/dev/null || { echo "build failed" >&2; exit 1; }
. tools/smoke-lib.sh

ws="$(mktemp -d)"
write_input() { printf '%s' "$1" >"$ws/input.json"; }

cat >"$ws/latency.csv" <<'CSV'
second,p50,p99
0,100,300
1,120,340
2,110,900
CSV

# --- two series, exact range, points scaled into the frame
write_input '{"pattern": "latency.csv", "title": "Latency", "yLabel": "ms"}'
run "$ws" >"$ws/atom.log" 2>&1 || fail "plot: $(cat "$ws/output.json") $(tail -3 "$ws/atom.log")"
[ "$(output "$ws" series)" = "2" ] || fail "series: $(cat "$ws/output.json")"
[ "$(output "$ws" points)" = "6" ] || fail "points: $(cat "$ws/output.json")"
[ "$(output "$ws" minY)" = "100" ] || fail "minY: $(cat "$ws/output.json")"
[ "$(output "$ws" maxY)" = "900" ] || fail "maxY: $(cat "$ws/output.json")"
[ -f "$ws/out/plot/plot.svg" ] || fail "no chart written"

python3 - "$ws/out/plot/plot.svg" <<'PY' || fail "the chart geometry is wrong"
import re, sys
svg = open(sys.argv[1], encoding="utf-8").read()
assert svg.startswith("<?xml") and svg.rstrip().endswith("</svg>"), svg[:80]
polylines = re.findall(r'<polyline[^>]*points="([^"]+)"', svg)
assert len(polylines) == 2, polylines
parsed = []
for points in polylines:
    pairs = [tuple(float(v) for v in p.split(",")) for p in points.split()]
    assert len(pairs) == 3, pairs
    xs = [p[0] for p in pairs]
    assert xs == sorted(xs), xs                      # x increases to the right
    assert all(40 <= y <= 420 for _, y in pairs), pairs   # y stays inside the frame
    parsed.append(pairs)
# p99 is the second column and holds the larger values, so its line sits higher (smaller y) throughout
assert min(y for _, y in parsed[1]) < min(y for _, y in parsed[0]), parsed
assert "Latency" in svg and "p50" in svg and "p99" in svg and "ms" in svg, "labels missing"
PY

# --- picking columns by name, and by index
write_input '{"pattern": "latency.csv", "yColumns": "p99", "xColumn": "second"}'
run "$ws" >"$ws/atom.log" 2>&1 || fail "column by name: $(cat "$ws/output.json")"
[ "$(output "$ws" series)" = "1" ] || fail "one series: $(cat "$ws/output.json")"
[ "$(output "$ws" maxY)" = "900" ] || fail "p99 max: $(cat "$ws/output.json")"
write_input '{"pattern": "latency.csv", "yColumns": "2", "xColumn": "0"}'
run "$ws" >"$ws/atom.log" 2>&1 || fail "column by index: $(cat "$ws/output.json")"
[ "$(output "$ws" maxY)" = "900" ] || fail "index 2 is p99: $(cat "$ws/output.json")"

# --- a non-numeric row is skipped and counted, or refused when the caller says so
printf 'second,p50\n0,100\nn/a,120\n2,140\n' >"$ws/messy.csv"
write_input '{"pattern": "messy.csv"}'
run "$ws" >"$ws/atom.log" 2>&1 || fail "skipNonNumeric default should tolerate it: $(cat "$ws/output.json")"
[ "$(output "$ws" skipped)" = "1" ] || fail "skipped count: $(cat "$ws/output.json")"
write_input '{"pattern": "messy.csv", "skipNonNumeric": "false"}'
run "$ws" >"$ws/atom.log" 2>&1 && fail "skipNonNumeric=false must refuse the row"
grep -q "row 3 of messy.csv has no number" "$ws/output.json" || fail "message: $(cat "$ws/output.json")"

# --- two files, a legend entry per file and column
cp "$ws/latency.csv" "$ws/latency-copy.csv"
write_input '{"pattern": "latency*.csv", "yColumns": "p50"}'
run "$ws" >"$ws/atom.log" 2>&1 || fail "two files: $(cat "$ws/output.json")"
[ "$(output "$ws" series)" = "2" ] || fail "one series per file: $(cat "$ws/output.json")"
grep -q "latency-copy.csv p50" "$ws/out/plot/plot.svg" || fail "the legend should name the file"

# --- a column that is not there names the header
write_input '{"pattern": "latency.csv", "yColumns": "p95"}'
run "$ws" >"$ws/atom.log" 2>&1 && fail "an unknown column must fail"
grep -q "column 'p95' is not in latency.csv" "$ws/output.json" || fail "message: $(cat "$ws/output.json")"
grep -q "second, p50, p99" "$ws/output.json" || fail "the header should be quoted: $(cat "$ws/output.json")"

# --- no CSV at all
write_input '{"pattern": "nothing-*.csv"}'
run "$ws" >"$ws/atom.log" 2>&1 && fail "no data must fail"
grep -q "no CSV matched" "$ws/output.json" || fail "message: $(cat "$ws/output.json")"

echo "smoke ok: two series with exact geometry, column by name and index, skipped rows, multi-file legend, unknown column, no data"
