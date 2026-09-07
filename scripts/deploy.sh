#!/usr/bin/env bash
# Uses standard SSH password prompt. Passwords are never put in command arguments.
set -euo pipefail
cd "$(dirname "$0")/.."
if [ ! -f .env ]; then echo '请先复制 .env.example 为 .env 并填写部署配置'; exit 1; fi
# Parse only connection settings as plain values; never execute .env as shell code.
get_setting() { node --input-type=module -e 'import {readFileSync} from "node:fs"; import {parseEnv} from "node:util"; process.stdout.write(parseEnv(readFileSync(".env","utf8"))[process.argv[1]]||"")' "$1"; }
star_host=$(get_setting ECS_HOST)
star_user=$(get_setting ECS_USER)
star_port=$(get_setting ECS_SSH_PORT)
star_dir=$(get_setting ECS_APP_DIR)
if [[ ! "$star_host" =~ ^[a-zA-Z0-9.-]+$ ]] || [[ ! "$star_user" =~ ^[a-zA-Z0-9_-]+$ ]] || [[ ! "$star_port" =~ ^[0-9]+$ ]] || [[ ! "$star_dir" =~ ^/[a-zA-Z0-9_/-]+$ ]]; then echo 'ECS 连接参数不完整或含无效字符'; exit 1; fi
if node --input-type=module -e 'import {readFileSync} from "node:fs";import {parseEnv} from "node:util";process.exit(parseEnv(readFileSync(".env","utf8")).ECS_PASSWORD?0:1)'; then
  export SSH_ASKPASS="$PWD/scripts/ssh-askpass.mjs"
  export SSH_ASKPASS_REQUIRE=force
  export DISPLAY="${DISPLAY:-codex}"
fi
ssh_opts=(-o StrictHostKeyChecking=accept-new -p "$star_port")
star_key=$(get_setting ECS_SSH_KEY_PATH)
if [ -n "$star_key" ]; then ssh_opts+=(-i "$star_key"); fi
# This is a user-invoked deploy command. Existing database volumes are retained.
ssh "${ssh_opts[@]}" "$star_user@$star_host" "command -v docker && docker compose version && mkdir -p '$star_dir'"
tar --exclude='.git' --exclude='node_modules' --exclude='dist' --exclude='data' --exclude='backups' --exclude='android' --exclude='docs' --exclude='artifacts' --exclude='test-results' --exclude='playwright-report' --exclude='.env*' -czf - . | ssh "${ssh_opts[@]}" "$star_user@$star_host" "tar -xzf - -C '$star_dir'"
# Send a minimal server env. SSH connection data and local secrets stay on the Mac.
node --input-type=module -e 'import {readFileSync} from "node:fs";import {parseEnv} from "node:util";const e=parseEnv(readFileSync(".env","utf8"));for(const k of ["DOMAIN","ADMIN_USERNAME","ADMIN_PASSWORD"]){if(!e[k])throw new Error("缺少 "+k);process.stdout.write(k+"="+JSON.stringify(e[k])+"\n");}' | ssh "${ssh_opts[@]}" "$star_user@$star_host" "umask 077; cat > '$star_dir/.env'"
ssh -t "${ssh_opts[@]}" "$star_user@$star_host" "cd '$star_dir' && docker compose up -d --build && docker compose ps"
