#!/usr/bin/env bash
# openfy version grammar and versionCode formula (docs/UPDATER.md §1.2).
# Keep in sync with versionFromName() in app/build.gradle.kts.
#
#   tools/version-code.sh v1.2.3        -> prints "1.2.3 1020399"   (versionName versionCode)
#   tools/version-code.sh --selftest    -> checks the spec test vectors
#
# Accepts a tag (leading "v" required) and prints the versionName and versionCode, or exits 1.
set -euo pipefail

# No leading zeros: v01.2.3 would collide with v1.2.3. rc number is 1..98, so 1-2 digits, first non-zero.
TAG_RE='^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-rc\.([1-9][0-9]?))?$'

# version_code_from_tag <tag>: prints "<versionName> <versionCode>", returns 1 on an invalid tag.
version_code_from_tag() {
  local tag=$1
  [[ $tag =~ $TAG_RE ]] || { echo "invalid tag '$tag': expected vX.Y.Z or vX.Y.Z-rc.N" >&2; return 1; }
  local x=${BASH_REMATCH[1]} y=${BASH_REMATCH[2]} z=${BASH_REMATCH[3]} n=${BASH_REMATCH[5]:-}
  # Force base 10 ("08" is not octal) and bound the digit count before arithmetic.
  if (( ${#x} > 4 || ${#y} > 2 || ${#z} > 2 || ${#n} > 2 )); then
    echo "invalid tag '$tag': number out of range (X<=2000, Y,Z<=99, 1<=N<=98)" >&2; return 1
  fi
  x=$((10#$x)); y=$((10#$y)); z=$((10#$z))
  local last=99
  if [[ -n $n ]]; then
    n=$((10#$n)); last=$n
    (( n >= 1 && n <= 98 )) || { echo "invalid tag '$tag': rc number must be 1..98" >&2; return 1; }
  fi
  (( x <= 2000 && y <= 99 && z <= 99 )) || { echo "invalid tag '$tag': X<=2000, Y,Z<=99" >&2; return 1; }
  echo "${tag#v} $(( x * 1000000 + y * 10000 + z * 100 + last ))"
}

selftest() {
  local fail=0 tag name code got
  while read -r tag name code; do
    got=$(version_code_from_tag "$tag") || got=ERROR
    if [[ $got != "$name $code" ]]; then echo "FAIL $tag: got '$got', want '$name $code'"; fail=1; fi
  done <<'EOF'
v0.1.0 0.1.0 10099
v0.2.0-rc.1 0.2.0-rc.1 20001
v0.2.0-rc.12 0.2.0-rc.12 20012
v0.2.0 0.2.0 20099
v1.0.0 1.0.0 1000099
v1.2.3 1.2.3 1020399
v2000.99.99 2000.99.99 2000999999
v0.0.1-rc.98 0.0.1-rc.98 198
v0.8.9 0.8.9 80999
EOF
  for tag in 1.0.0 v1.0 v1.0.0-rc.0 v1.0.0-rc.99 v1.0.0-rc.00 v1.0.0-rc.01 v1.0.0-rc.001 v01.0.0 v1.00.0 v1.0.00 v00.0.0 v1.0.0-rc v1.0.0-beta.1 v1.100.0 v1.0.100 v2001.0.0 v99999999999.0.0 "v1.0.0 " ""; do
    if version_code_from_tag "$tag" >/dev/null 2>&1; then echo "FAIL '$tag' should be rejected"; fail=1; fi
  done
  (( fail == 0 )) && echo "version-code selftest OK"
  return "$fail"
}

case "${1:-}" in
  --selftest) selftest ;;
  "") echo "usage: $0 <tag> | --selftest" >&2; exit 2 ;;
  *) version_code_from_tag "$1" ;;
esac
