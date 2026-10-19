ARG KYLIN_BASE=macrosan/kylin:v10-sp3-2403
FROM node:24-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM scratch AS web-artifacts
COPY --from=build /app/dist/ /

FROM ${KYLIN_BASE} AS nginx-build
RUN yum install -y nginx
COPY prepare-nginx.sh /tmp/prepare-nginx.sh
RUN sed -i 's/\r$//' /tmp/prepare-nginx.sh && sh /tmp/prepare-nginx.sh

FROM scratch AS nginx-artifacts
COPY --from=nginx-build /out/ /

FROM ${KYLIN_BASE} AS runtime
# The Kylin base already supplies the other shared libraries and account tools.
COPY --from=nginx-artifacts / /
RUN nginx -v \
    && groupadd --gid 10001 flinkweb \
    && useradd --uid 10001 --gid 10001 --no-create-home flinkweb
COPY nginx-main.conf /etc/nginx/nginx.conf
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker-entrypoint.sh /usr/local/bin/flink-web-entrypoint.sh
RUN sed -i 's/\r$//' /usr/local/bin/flink-web-entrypoint.sh \
    && chmod 755 /usr/local/bin/flink-web-entrypoint.sh
COPY --from=web-artifacts / /usr/share/nginx/html/
EXPOSE 8080

USER 10001:10001
ENTRYPOINT ["/bin/sh", "/usr/local/bin/flink-web-entrypoint.sh"]
CMD ["nginx", "-g", "daemon off;"]
