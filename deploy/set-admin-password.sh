#!/usr/bin/env bash
# Sets the login and password Caddy asks for on https://$JAMAL_DOMAIN/admin.
# On the server: /opt/jamal/set-admin-password.sh   From a Mac: ssh -t root@SERVER /opt/jamal/set-admin-password.sh
# Running it again replaces the login and password.
set -euo pipefail
cd "$(dirname "$0")"
domain=$(sed -n 's/^JAMAL_DOMAIN=//p' .env | tail -1)

read -r -p "Login [admin]: " login
login=${login:-admin}
if [[ ! $login =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "Use only Latin letters, digits, dot, dash and underscore in the login." >&2; exit 1
fi
read -r -s -p "Password: " pw1; echo
read -r -s -p "Password again: " pw2; echo
if [[ ${#pw1} -lt 8 ]]; then echo "The password is shorter than 8 characters." >&2; exit 1; fi
if [[ $pw1 != "$pw2" ]]; then echo "The passwords do not match." >&2; exit 1; fi

# The password reaches Caddy through stdin, so it never shows up in the process list.
hash=$(printf '%s\n' "$pw1" | docker compose exec -T caddy caddy hash-password --algorithm bcrypt)
unset pw1 pw2
if [[ $hash != \$2* ]]; then echo "Could not hash the password." >&2; exit 1; fi

umask 077
mkdir -p admin-auth
printf 'basic_auth bcrypt {\n\t%s %s\n}\n' "$login" "$hash" > admin-auth/basic.caddy
[[ -f admin-auth/locked.caddy ]] && mv admin-auth/locked.caddy admin-auth/locked.caddy.off

caddy_cmd() { docker compose exec -T caddy caddy "$@" --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1; }
if caddy_cmd validate && caddy_cmd reload; then
  rm -f admin-auth/locked.caddy.off
  echo "Done: https://${domain:-your-domain}/admin (login: $login)"
else
  rm -f admin-auth/basic.caddy
  [[ -f admin-auth/locked.caddy.off ]] && mv admin-auth/locked.caddy.off admin-auth/locked.caddy
  echo "Caddy rejected the change; the dashboard stays closed. See: docker compose logs caddy" >&2
  exit 1
fi
