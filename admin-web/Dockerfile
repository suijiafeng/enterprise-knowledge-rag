# 管理控制台镜像：Node 构建静态文件 → nginx 托管，并把 /api 反向代理到服务端（同源，无需 CORS）。
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
# 演示账号入口只在显式开启时编进产物（默认关闭；compose 的本地演示会打开）。
ARG VITE_ZHIKU_DEMO_LOGIN=false
ENV VITE_ZHIKU_DEMO_LOGIN=$VITE_ZHIKU_DEMO_LOGIN
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80
