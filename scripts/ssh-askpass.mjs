#!/usr/bin/env node
import { readFileSync } from 'node:fs';
import { parseEnv } from 'node:util';
import { fileURLToPath } from 'node:url';
// Invoked only by SSH. Never log this output or call this script directly.
if (!/password/i.test(process.argv.slice(2).join(' '))) process.exit(1);
const config = parseEnv(readFileSync(fileURLToPath(new URL('../.env', import.meta.url)), 'utf8'));
if (!config.ECS_PASSWORD) process.exit(1);
process.stdout.write(config.ECS_PASSWORD + '\n');
