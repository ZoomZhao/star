FROM node:24.14.0-bookworm-slim AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY tsconfig.json vite.config.ts index.html ./
COPY src ./src
COPY public ./public
RUN npm run build

FROM node:24.14.0-bookworm-slim AS runtime
ENV NODE_ENV=production APP_ENV=production HOST=0.0.0.0 PORT=3001
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev && npm cache clean --force && mkdir data backups && chown node:node data backups
COPY --from=build /app/dist ./dist
COPY server ./server
USER node
EXPOSE 3001
CMD ["node", "server/index.mjs"]
