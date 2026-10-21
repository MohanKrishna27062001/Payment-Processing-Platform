# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------------------------------
# Build. `npm run build` runs `tsc --noEmit && vite build`, so a type error fails the image build
# rather than shipping a broken bundle.
# ---------------------------------------------------------------------------------------------------
# --platform=$BUILDPLATFORM: the bundle is plain HTML/JS/CSS, identical for every CPU, so build it natively
# even when producing an arm64 image (e.g. for Apple Silicon) instead of running npm under emulation.
FROM --platform=$BUILDPLATFORM node:22-alpine AS build
WORKDIR /app

# package-lock.json first so `npm ci` is cached independently of the source.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---------------------------------------------------------------------------------------------------
# Serve. Static files from nginx, which also proxies /api to the backend so the browser only ever
# talks to one origin -- no CORS, exactly as the Vite dev proxy behaves.
# ---------------------------------------------------------------------------------------------------
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
# 127.0.0.1, not localhost: in Alpine localhost resolves to ::1 first, and nginx listens on IPv4 only, so a
# localhost check fails forever and marks a working container unhealthy.
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=5 \
    CMD wget -qO- http://127.0.0.1/ >/dev/null 2>&1 || exit 1
