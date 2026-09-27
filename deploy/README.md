# Production deployment

Jamal runs behind Caddy, which serves HTTPS with an automatic Let's Encrypt
certificate and asks for a login on the admin dashboard. The app itself is only
published on the server's loopback interface.

Server requirements: Linux x86_64 (amd64), Docker Engine with the Compose
plugin, ports 80 and 443 open. Rendering runs on the CPU (no GPU), so dedicated
vCPUs matter far more than anything else; shared-CPU plans are several times
slower.

## First deployment

```sh
mkdir -p /opt/jamal && cd /opt/jamal
git clone https://github.com/artemmelnik/cutvid-editor.git src
cp -r src/deploy/compose.yaml src/deploy/Caddyfile src/deploy/set-admin-password.sh src/deploy/admin-auth .
cp src/deploy/env.example .env && chmod 600 .env
nano .env                       # JAMAL_DOMAIN, JAMAL_PUBLIC_URL, optionally JAMAL_SERVER_IP
docker compose up -d --build    # first build takes several minutes
./set-admin-password.sh         # opens https://<domain>/admin behind a login
```

The site is then at `https://<JAMAL_DOMAIN>`. Without a domain, use
`<ip-with-dashes>.sslip.io` (for example `46-101-103-174.sslip.io`), which
resolves to the server's IP and still gets a real certificate.

## Updating

```sh
cd /opt/jamal/src && git pull
cd /opt/jamal && docker compose up -d --build
```

Renders that are running when the app restarts are marked as failed and need
to be started again. Uploaded videos, projects, render history, exports and the
activity log live in the `jamal_jamal-data` volume and survive updates.

## Admin dashboard

`https://<domain>/admin` after `set-admin-password.sh` (run it again to change
the password). Without a password it is also reachable through an SSH tunnel:

```sh
ssh -N -L 8787:127.0.0.1:8787 root@<server>   # then open http://localhost:8787/admin
```

The app refuses admin requests that come from a public address, so the
dashboard stays closed even if port 8787 is ever exposed directly.

## ChatGPT access (MCP)

Works through Caddy as is: `/mcp/…` and the signed `/files/…` download links
are proxied like the rest of the site, and `JAMAL_PUBLIC_URL` provides the
address the links point to. Each user creates their link on the site with
**Create ChatGPT link** (see the main README). The link carries its secret in
the URL and the app never logs it; Caddy keeps no access log in this setup, so
if you ever add a `log` directive to the Caddyfile, exclude `/mcp/*` from it.

## Disk space

Uploads, project videos and exports are never deleted automatically. Check
usage with `docker system df -v` and remove old files deliberately, for
example exports older than 30 days:

```sh
docker compose exec jamal find /data/exports -type f -mtime +30 -delete
```
