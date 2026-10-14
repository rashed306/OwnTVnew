#!/usr/bin/env bash
# OwnTVnew: use the libmpv engine built in rashed306/OwnTV_libmpv (adds DVB teletext) instead of
# OwnTV's published one. Downloads the newest release AAR, lays it out as a one-artifact Maven
# repository with version "local", and points the build at it through owntv.libmpvLocalRepo
# (settings.gradle.kts adds the repository; app/build.gradle.kts substitutes the dependency).
# No release yet, or the download fails: the build keeps the stock engine and says so.
set -uo pipefail
repo="rashed306/OwnTV_libmpv"
work="$RUNNER_TEMP/libmpv-dl"
maven="$RUNNER_TEMP/libmpv-maven"
dest="$maven/tv/own/owntv/libmpv/local"
mkdir -p "$work" "$dest"
if ! gh release download --repo "$repo" --pattern libmpv-release.aar --dir "$work" --clobber; then
  echo "::warning title=Stock engine::No teletext libmpv release found in $repo; building with OwnTV's published engine."
  exit 0
fi
tag=$(gh release view --repo "$repo" --json tagName --jq .tagName 2>/dev/null || echo unknown)
cp "$work/libmpv-release.aar" "$dest/libmpv-local.aar"
cat > "$dest/libmpv-local.pom" <<POM
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>tv.own.owntv</groupId>
  <artifactId>libmpv</artifactId>
  <version>local</version>
  <packaging>aar</packaging>
</project>
POM
echo "owntv.libmpvLocalRepo=$maven" >> gradle.properties
echo "::notice title=Teletext engine::Building with libmpv from $repo release $tag"
