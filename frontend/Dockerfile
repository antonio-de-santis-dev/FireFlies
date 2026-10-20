# Immagine di produzione del sito (FASE 8): build di Vite, poi Caddy serve i file statici e fa da reverse proxy verso il
# backend (stessa origine: cookie di sessione e CSRF senza CORS). HTTPS automatico con Let's Encrypt.
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts
COPY . .
RUN npm run build

FROM caddy:2-alpine
COPY --from=build /src/dist /srv
COPY Caddyfile /etc/caddy/Caddyfile
