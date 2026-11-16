#!/usr/bin/env bash
# Checks that the version declared in app/build.gradle.kts and the fastlane
# metadata are consistent with what F-Droid expects.
#
# Usage: check-fdroid-metadata.sh [expected-version-name]
#
# When an expected version name is given (release builds pass the tag without
# its leading "v"), it must match versionName.
set -euo pipefail
export LC_ALL=C.UTF-8

cd "$(dirname "$(readlink -f "$0")")/../.."

GRADLE_FILE="app/build.gradle.kts"
FASTLANE_DIR="fastlane/metadata/android"
DEFAULT_LOCALE="en-US"

# Limits enforced by fdroidserver.
MAX_TITLE=50
MAX_SHORT=80
MAX_FULL=4000
MAX_CHANGELOG=500

errors=0
fail() {
	echo "ERROR: $*" >&2
	errors=$((errors + 1))
}

# Character count without the trailing newlines.
char_count() {
	printf '%s' "$(cat "$1")" | wc -m
}

check_length() {
	local file="$1" max="$2" count
	[ -f "$file" ] || return 0
	count="$(char_count "$file")"
	if [ "$count" -gt "$max" ]; then
		fail "$file: $count characters, maximum is $max"
	fi
}

version_name="$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' "$GRADLE_FILE")"
version_code="$(sed -n 's/^ *versionCode = \([0-9][0-9]*\)$/\1/p' "$GRADLE_FILE")"

if [ -z "$version_name" ] || [ -z "$version_code" ]; then
	echo "ERROR: cannot read versionName/versionCode from $GRADLE_FILE" >&2
	exit 1
fi
echo "versionName=$version_name versionCode=$version_code"

if [ -n "${1:-}" ] && [ "$1" != "$version_name" ]; then
	fail "expected version '$1' but $GRADLE_FILE declares '$version_name'"
fi

for required in title.txt short_description.txt full_description.txt "changelogs/${version_code}.txt"; do
	[ -f "$FASTLANE_DIR/$DEFAULT_LOCALE/$required" ] ||
		fail "missing $FASTLANE_DIR/$DEFAULT_LOCALE/$required"
done

for locale_dir in "$FASTLANE_DIR"/*/; do
	locale_dir="${locale_dir%/}"
	check_length "$locale_dir/title.txt" "$MAX_TITLE"
	check_length "$locale_dir/short_description.txt" "$MAX_SHORT"
	check_length "$locale_dir/full_description.txt" "$MAX_FULL"
	for changelog in "$locale_dir"/changelogs/*.txt; do
		[ -e "$changelog" ] || continue
		check_length "$changelog" "$MAX_CHANGELOG"
		case "$(basename "$changelog" .txt)" in
			*[!0-9]*) fail "$changelog: file name must be a versionCode" ;;
		esac
	done
done

# fdroidserver only publishes PNG and JPEG graphics.
while IFS= read -r -d '' image; do
	case "${image##*.}" in
		png | jpg | jpeg) ;;
		*) fail "$image: unsupported image format (use png, jpg or jpeg)" ;;
	esac
done < <(find "$FASTLANE_DIR" -path '*/images/*' -type f -print0)

if [ "$errors" -gt 0 ]; then
	echo "$errors problem(s) found" >&2
	exit 1
fi
echo "F-Droid metadata OK"

if [ -n "${GITHUB_OUTPUT:-}" ]; then
	{
		echo "version_name=$version_name"
		echo "version_code=$version_code"
	} >> "$GITHUB_OUTPUT"
fi
