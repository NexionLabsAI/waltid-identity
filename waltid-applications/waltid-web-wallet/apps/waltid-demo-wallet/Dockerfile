FROM gplane/pnpm:9.6 AS base

RUN apt-get update && apt-get install -y \
    python3 \
    make \
    g++ \
    gcc \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# install dependencies & build
FROM base AS prerelease

COPY waltid-applications/waltid-web-wallet/package.json ./
COPY waltid-applications/waltid-web-wallet/apps/waltid-demo-wallet/package.json ./apps/waltid-demo-wallet/


RUN cd apps/waltid-demo-wallet && pnpm install && \
    pnpm add -D db0@0.1.4 @libsql/client@0.5.2 better-sqlite3@9.4.3 drizzle-orm@0.29.4

COPY waltid-applications/waltid-web-wallet .
RUN cd apps/waltid-demo-wallet && pnpm run build

# copy build output into final image
FROM base AS release
ENV NODE_ENV=production

COPY --from=prerelease /app/apps/waltid-demo-wallet/.output/ .

# run the app
RUN chmod -R a+r .
# required for pnpm
RUN groupadd -r app && useradd -r -g app app
USER app

EXPOSE 7101/tcp
ENV PORT=7101
ENTRYPOINT [ "node", "server/index.mjs" ]