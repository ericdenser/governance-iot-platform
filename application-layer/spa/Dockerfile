FROM node:20-alpine AS build
WORKDIR /app

ARG PROXY_URL=
ARG NPM_NO_PROXY=localhost,127.0.0.1
ENV HTTP_PROXY=${PROXY_URL} HTTPS_PROXY=${PROXY_URL} NO_PROXY=${NPM_NO_PROXY}

COPY package*.json ./
RUN npm ci --maxsockets 1

COPY . .

ARG VITE_BFF_URL
ARG VITE_BASE_PATH=/
ENV VITE_BFF_URL=$VITE_BFF_URL
ENV VITE_BASE_PATH=$VITE_BASE_PATH

RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
