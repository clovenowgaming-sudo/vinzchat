#!/usr/bin/env bash
# Jalankan di Termux: bash push.sh [nama-repo]
set -e
REPO="${1:-vinzchat}"
pkg install -y git gh >/dev/null 2>&1 || true
gh auth status >/dev/null 2>&1 || gh auth login
git init -b main >/dev/null 2>&1 || true
git add -A
git commit -m "Vinz Chat" >/dev/null 2>&1 || true
gh repo create "$REPO" --private --source=. --remote=origin --push
echo "Menunggu workflow..."; sleep 8
RUN=$(gh run list --limit 1 --json databaseId -q '.[0].databaseId')
gh run watch "$RUN" --exit-status
mkdir -p hasil && gh release download --dir hasil --pattern '*'
echo "Selesai. File ada di folder hasil/"
