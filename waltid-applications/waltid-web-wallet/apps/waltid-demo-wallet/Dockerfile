FROM gplane/pnpm:9.6 AS base

WORKDIR /app

# install dependencies & build
FROM base AS prerelease

# Copy root workspace files
COPY waltid-applications/waltid-web-wallet/package.json ./
COPY waltid-applications/waltid-web-wallet/package-lock.json ./

# Create directory and copy app package.json
RUN mkdir -p ./apps/waltid-demo-wallet
COPY waltid-applications/waltid-web-wallet/apps/waltid-demo-wallet/package.json ./apps/waltid-demo-wallet/

# Install dependencies
RUN pnpm install

COPY waltid-applications/waltid-web-wallet .

# Ensure Nuxt dependencies are installed and build
RUN cd apps/waltid-demo-wallet && \
    pnpm install && \
    pnpm run build

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