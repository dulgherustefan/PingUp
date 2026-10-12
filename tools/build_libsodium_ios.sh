#!/bin/sh
# Builds libsodium as static libraries for the iOS targets of :core, which link it through Kotlin/Native cinterop.
# Gradle runs it before the first iOS build (core/build.gradle.kts); by hand:
#
#   tools/build_libsodium_ios.sh core/build/libsodium
#
# leaves ios-arm64/ and ios-simulator-arm64/, each with include/ and lib/libsodium.a. A target already built is
# skipped. Needs Xcode. The source is the release tarball; its SHA-256 is pinned here, after checking the tarball's
# minisign signature against the key on doc.libsodium.org (RWQf6LRCGA9i53mlYecO4IzT51TGPpvWucNSCh1CBM0QTaLn73Y7GFO3).
set -eu

VERSION=1.0.22
SHA256=adbdd8f16149e81ac6078a03aca6fc03b592b89ef7b5ed83841c086191be3349
URL="https://github.com/jedisct1/libsodium/releases/download/$VERSION-RELEASE/libsodium-$VERSION.tar.gz"
# Kotlin/Native's own minimum (minVersion.ios in konan.properties), so the linker doesn't warn
IOS_MIN=14.0

OUT=$(mkdir -p "$1" && cd "$1" && pwd)
TARBALL="$OUT/libsodium-$VERSION.tar.gz"
SRC="$OUT/src/libsodium-$VERSION"

if [ ! -f "$TARBALL" ]; then
    curl -fsSL -o "$TARBALL.part" "$URL"
    mv "$TARBALL.part" "$TARBALL"
fi
if [ "$(shasum -a 256 "$TARBALL" | cut -d' ' -f1)" != "$SHA256" ]; then
    rm -f "$TARBALL"
    echo "libsodium-$VERSION.tar.gz doesn't match the pinned SHA-256" >&2
    exit 1
fi
if [ ! -d "$SRC" ]; then
    mkdir -p "$OUT/src"
    tar -xzf "$TARBALL" -C "$OUT/src"
fi

# $1: SDK, $2: target directory, $3: minimum version flag
build() {
    if [ -f "$OUT/$2/lib/libsodium.a" ]; then return; fi
    sdk=$(xcrun --sdk "$1" --show-sdk-path)
    log="$OUT/build-$2.log"
    if ! (
        cd "$SRC"
        make distclean || true
        CFLAGS="-O3 -arch arm64 -isysroot $sdk $3" LDFLAGS="-arch arm64 -isysroot $sdk $3" \
            ./configure --host=aarch64-apple-darwin23 --prefix="$OUT/$2" --disable-shared --enable-static
        make -j"$(sysctl -n hw.ncpu)" install
    ) > "$log" 2>&1; then
        tail -40 "$log" >&2
        exit 1
    fi
}

build iphoneos ios-arm64 "-mios-version-min=$IOS_MIN"
build iphonesimulator ios-simulator-arm64 "-mios-simulator-version-min=$IOS_MIN"
