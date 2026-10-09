#!/usr/bin/env bash
#
# Builds a signed release APK from a git tag: scripts/release.sh 0.2.0
#
# The tag is checked out into a temporary worktree, so the build contains exactly what was
# tagged and the working copy is left alone. The version comes from the tag name, and the APK
# lands in dist/powergraph-<version>.apk. Signing reads POWERGRAPH_KEYSTORE and friends from
# ~/.gradle/gradle.properties; the tagged commit's app/build.gradle.kts must have the release
# signing config for the APK to be signed.

set -euo pipefail

if [ "$#" -ne 1 ]; then
    echo "Usage: $0 <tag>" >&2
    exit 64
fi

TAG="$1"
VERSION="${TAG#v}"
ROOT="$(git rev-parse --show-toplevel)"
APK_DIR="app/build/outputs/apk/release"
OUT="$ROOT/dist/powergraph-$VERSION.apk"

if ! git -C "$ROOT" rev-parse -q --verify "refs/tags/$TAG^{commit}" >/dev/null; then
    echo "No such tag: $TAG" >&2
    exit 1
fi

WORKTREE="$(mktemp -d "${TMPDIR:-/tmp}/powergraph-release.XXXXXX")"
cleanup() {
    git -C "$ROOT" worktree remove --force "$WORKTREE" >/dev/null 2>&1 || rm -rf "$WORKTREE"
}
trap cleanup EXIT

git -C "$ROOT" worktree add --detach "$WORKTREE" "$TAG" >/dev/null

if [ -f "$ROOT/local.properties" ]; then
    cp "$ROOT/local.properties" "$WORKTREE/local.properties"
fi

(cd "$WORKTREE" && ./gradlew --no-daemon -PversionName="$VERSION" testDebugUnitTest assembleRelease)

if [ ! -f "$WORKTREE/$APK_DIR/app-release.apk" ]; then
    echo "No signed APK was built; $TAG predates the release signing config in app/build.gradle.kts." >&2
    ls "$WORKTREE/$APK_DIR" >&2 || true
    exit 1
fi

mkdir -p "$ROOT/dist"
cp "$WORKTREE/$APK_DIR/app-release.apk" "$OUT"
echo "Built $OUT"
