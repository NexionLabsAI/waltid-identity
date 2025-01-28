FROM gplane/pnpm:9.6 AS base

WORKDIR /app


FROM base AS prerelease


COPY waltid-applications/waltid-web-wallet/package.json ./
COPY waltid-applications/waltid-web-wallet/package-lock.json ./

RUN mkdir -p ./apps/waltid-demo-wallet
COPY waltid-applications/waltid-web-wallet/apps/waltid-demo-wallet/package.json ./apps/waltid-demo-wallet/

RUN pnpm install

COPY waltid-applications/waltid-web-wallet .

RUN cd apps/waltid-demo-wallet && \
    pnpm install && \
    pnpm run build

FROM base AS release
ENV NODE_ENV=production

COPY --from=prerelease /app/apps/waltid-demo-wallet/.output/ .

RUN chmod -R a+r .

RUN groupadd -r app && useradd -r -g app app
USER app

EXPOSE 7101/tcp
ENV PORT=7101
ENTRYPOINT [ "node", "server/index.mjs" ]