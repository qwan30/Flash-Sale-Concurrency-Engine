# ── Build stage ──────────────────────────────────────────────
FROM node:20-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ── Runtime stage ────────────────────────────────────────────
FROM nginx:alpine
WORKDIR /usr/share/nginx/html

# Remove default nginx content
RUN rm -rf ./*

# Copy static assets from the Next.js build output
COPY --from=build /app/.next/static ./_next/static

# Copy public directory (favicons, robots.txt, etc.)
COPY --from=build /app/public ./

# Nginx config: serve static files + proxy /api to backend
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=3 \
    CMD wget -qO- http://localhost:80/ || exit 1
