#!/usr/bin/env sh
# Bootstrap only: run on the selected x86_64 Linux ECS before deploy-systemd.mjs.
set -eu
[ "$(uname -m)" = x86_64 ] || { echo 'This runtime bundle requires x86_64'; exit 1; }
mkdir -p /opt/star/runtime
cd /opt/star/runtime
curl -fsSLO https://nodejs.org/dist/v24.14.0/node-v24.14.0-linux-x64.tar.xz
curl -fsSLO https://nodejs.org/dist/v24.14.0/SHASUMS256.txt
sed -n '/ node-v24.14.0-linux-x64.tar.xz$/p' SHASUMS256.txt | sha256sum -c -
tar -xf node-v24.14.0-linux-x64.tar.xz
curl -fsSLO https://github.com/caddyserver/caddy/releases/download/v2.11.4/caddy_2.11.4_linux_amd64.tar.gz
curl -fsSLO https://github.com/caddyserver/caddy/releases/download/v2.11.4/caddy_2.11.4_checksums.txt
sed -n '/ caddy_2.11.4_linux_amd64.tar.gz$/p' caddy_2.11.4_checksums.txt | sha512sum -c -
tar -xzf caddy_2.11.4_linux_amd64.tar.gz caddy
./node-v24.14.0-linux-x64/bin/node --version
./caddy version
