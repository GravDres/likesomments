#!/usr/bin/env bash
# One-time personal setup; passwords and keystore contents are never printed.
set -euo pipefail
cd "$(dirname "$0")/.."
hod_bundle="${1:-$HOME/storage/downloads/Hod-Signing-Key.zip}"
hod_repo="${2:-GravDres/likesomments}"
hod_private="${HOD_SIGNING_DIRECTORY:-$HOME/.hod-signing}"
command -v gh >/dev/null || { echo 'Install GitHub CLI: pkg install gh'; exit 1; }
command -v unzip >/dev/null || { echo 'Install unzip: pkg install unzip'; exit 1; }
test -f "$hod_bundle" || { echo 'Download Hod-Signing-Key.zip to Downloads first.'; exit 1; }
hod_expected="$(tr -d '\r\n' < config/signing-certificate.sha256)"
hod_actual="$(unzip -p "$hod_bundle" certificate.sha256 | tr -d '\r\n')"
test "$hod_actual" = "$hod_expected" || { echo 'Signing bundle does not match this source version; existing keys were not changed.'; exit 1; }
umask 077
mkdir -p "$hod_private"
chmod 700 "$hod_private"
if test -f "$hod_private/certificate.sha256"; then
    test "$(tr -d '\r\n' < "$hod_private/certificate.sha256")" = "$hod_expected" || { echo 'A different signing key is already saved. Keep it; do not replace it automatically.'; exit 1; }
fi
unzip -n -q "$hod_bundle" -d "$hod_private"
test -s "$hod_private/keystore.base64" && test -s "$hod_private/keystore-password.txt"
chmod 600 "$hod_private"/*
if ! gh auth status --hostname github.com >/dev/null 2>&1; then
    gh auth login --hostname github.com --web --git-protocol https
fi
gh secret set HOD_KEYSTORE_BASE64 --repo "$hod_repo" < "$hod_private/keystore.base64"
gh secret set HOD_KEYSTORE_PASSWORD --repo "$hod_repo" < "$hod_private/keystore-password.txt"
echo 'Done: both signing secrets saved. Keep the signing ZIP as a private backup.'
echo 'Commit and push the updated source, then download the signed APK from Actions.'
