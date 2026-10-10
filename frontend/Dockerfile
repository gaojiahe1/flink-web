ARG KYLIN_BASE=macrosan/kylin:v10-sp3-2403
FROM node:24-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build
FROM ${KYLIN_BASE}
RUN yum install -y nginx shadow-utils \
    && yum clean all && rm -rf /var/cache/dnf /var/cache/yum \
    && chmod 755 /var/log/nginx \
    && ln -sf /dev/stderr /var/log/nginx/error.log \
    && groupadd --gid 10001 flinkweb \
    && useradd --uid 10001 --gid 10001 --no-create-home flinkweb
COPY nginx-main.conf /etc/nginx/nginx.conf
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker-entrypoint.sh /usr/local/bin/flink-web-entrypoint.sh
RUN sed -i 's/\r$//' /usr/local/bin/flink-web-entrypoint.sh \
    && chmod 755 /usr/local/bin/flink-web-entrypoint.sh
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080

USER 10001:10001
ENTRYPOINT ["/bin/sh", "/usr/local/bin/flink-web-entrypoint.sh"]
CMD ["nginx", "-g", "daemon off;"]
