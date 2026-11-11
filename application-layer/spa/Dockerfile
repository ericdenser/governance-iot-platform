FROM node:22-alpine AS build
WORKDIR /app

ARG PROXY_URL=
ARG NPM_NO_PROXY=localhost,127.0.0.1

COPY package*.json ./
# proxy only for this step, so it is not stored in the image config
RUN HTTP_PROXY="$PROXY_URL" HTTPS_PROXY="$PROXY_URL" NO_PROXY="$NPM_NO_PROXY" npm ci --maxsockets 1

COPY . .

ARG VITE_BFF_URL
ARG VITE_BASE_PATH=/
ARG VITE_CARTO_API_KEY=
ENV VITE_BFF_URL=$VITE_BFF_URL
ENV VITE_BASE_PATH=$VITE_BASE_PATH
ENV VITE_CARTO_API_KEY=$VITE_CARTO_API_KEY

RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
