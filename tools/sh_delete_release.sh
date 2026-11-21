#!/usr/bin/env bash

REPO=gildas-le-drogoff/TokenUsage...
gh run list -R "$REPO" -L 1000 --json databaseId,conclusion --jq '.[] | select(.conclusion|IN("success","failure")) | .databaseId' | xargs -P 8 -I{} gh run delete {} -R "$REPO"
