#!/usr/bin/env bash

set -e

RED="\033[91m"
GREEN="\033[92m"
YELLOW="\033[93m"
CYAN="\033[96m"
GRAY="\033[90m"
BOLD="\033[1m"
RESET="\033[0m"

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

Removed=0
Bytes=0

Remove() {
    local Target="$1"
    if [ -e "$Target" ] || [ -L "$Target" ]; then
        local Size
        Size=$(du -sb "$Target" 2>/dev/null | awk '{print $1}' || echo 0)
        rm -rf "$Target"
        Bytes=$((Bytes + Size))
        Removed=$((Removed + 1))
        printf "  ${RED}removed${RESET}  ${GRAY}%s${RESET}\n" "${Target#$ROOT/}"
    fi
}

printf "\n${BOLD}${CYAN}HellcatAPI — Git Clean${RESET}\n"
printf "${GRAY}%s${RESET}\n\n" "$(printf '─%.0s' {1..40})"

printf "${BOLD}Build output${RESET}\n"
Remove "$ROOT/target"
Remove "$ROOT/HellcatAPI.jar"
Remove "$ROOT/original-HellcatAPI.jar"
Remove "$ROOT/dependency-reduced-pom.xml"

printf "\n${BOLD}Database files${RESET}\n"
Remove "$ROOT/hellcat.db"
Remove "$ROOT/hellcat.db-shm"
Remove "$ROOT/hellcat.db-wal"
for F in "$ROOT"/*.db "$ROOT"/*.db-shm "$ROOT"/*.db-wal "$ROOT"/*.sqlite "$ROOT"/*.sqlite3; do
    [ -e "$F" ] && Remove "$F"
done

printf "\n${BOLD}Certificates & keys${RESET}\n"
Remove "$ROOT/certs"
for F in "$ROOT"/*.pem "$ROOT"/*.key "$ROOT"/*.crt "$ROOT"/*.p12 "$ROOT"/*.jks; do
    [ -e "$F" ] && Remove "$F"
done

printf "\n${BOLD}IDE & OS files${RESET}\n"
Remove "$ROOT/.idea"
Remove "$ROOT/.vscode"
Remove "$ROOT/.settings"
Remove "$ROOT/.classpath"
Remove "$ROOT/.project"
for F in "$ROOT"/.DS_Store "$ROOT"/Thumbs.db "$ROOT"/*.iml; do
    [ -e "$F" ] && Remove "$F"
done

printf "\n${BOLD}Logs${RESET}\n"
for F in "$ROOT"/*.log "$ROOT"/logs; do
    [ -e "$F" ] && Remove "$F"
done

printf "\n${BOLD}Temp files${RESET}\n"
for F in "$ROOT"/*.tmp "$ROOT"/*.bak "$ROOT"/*.swp "$ROOT"/*~; do
    [ -e "$F" ] && Remove "$F"
done

if [ "$Bytes" -ge 1048576 ]; then
    Size="$(echo "scale=1; $Bytes/1048576" | bc) MB"
elif [ "$Bytes" -ge 1024 ]; then
    Size="$(echo "scale=1; $Bytes/1024" | bc) KB"
else
    Size="${Bytes} B"
fi

printf "\n${GRAY}%s${RESET}\n" "$(printf '─%.0s' {1..40})"
if [ "$Removed" -eq 0 ]; then
    printf "${GREEN}Already clean — nothing to remove.${RESET}\n\n"
else
    printf "${GREEN}${BOLD}Done.${RESET} Removed ${BOLD}${Removed}${RESET} item(s), freed ${BOLD}${Size}${RESET}.\n\n"
fi
