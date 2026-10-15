#!/usr/bin/env bash
# OwnTVnew: build against our fork of OwnTV_Core (rashed306/OwnTV_Core, branch owntvnew) instead of
# the published core/player-core artifacts, so player changes (ExoPlayer teletext, subtitle timing,
# OCR hooks) reach the app. settings.gradle.kts includes the checkout through owntv.corePath, and
# Gradle substitutes tv.own.owntv:core and :player-core with its modules.
set -euo pipefail
repo="https://github.com/rashed306/OwnTV_Core.git"
branch="owntvnew"
dir="$RUNNER_TEMP/owntv-core"
rm -rf "$dir"
git clone --depth 1 --branch "$branch" "$repo" "$dir"
echo "owntv.corePath=$dir" >> gradle.properties
echo "::notice title=Core fork::Building with OwnTV_Core from rashed306 ($branch @ $(git -C "$dir" rev-parse --short HEAD))"
