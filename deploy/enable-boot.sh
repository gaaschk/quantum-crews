#!/bin/bash
# SPDX-License-Identifier: MIT
set -euo pipefail
root="${1:?Pass the deployment root directory}"
label=com.quantumhorizon.quantum-crews
sudo install -o root -g wheel -m 644 "$root/boot-service.plist" "/Library/LaunchDaemons/$label.plist"
sudo launchctl bootstrap system "/Library/LaunchDaemons/$label.plist"
launchctl bootout "user/$(id -u)/$label" 2>/dev/null || true
launchctl bootout "gui/$(id -u)/$label" 2>/dev/null || true
printf 'Installed boot service %s; execution remains under the deployment user.\n' "$label"
