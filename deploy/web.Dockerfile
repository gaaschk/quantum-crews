FROM node:22-bookworm-slim AS build
ARG SOURCE_REVISION=local
ENV QUANTUM_SOURCE_REVISION=$SOURCE_REVISION
WORKDIR /src
ENV NEXT_TELEMETRY_DISABLED=1 API_BASE_URL=http://api:8100
COPY package.json package-lock.json ./
COPY apps/web/package.json apps/web/package.json
RUN --mount=type=cache,target=/root/.npm npm ci
COPY apps/web apps/web
RUN npm run build
# Node base has no Python; preserve the legal files using Node itself.
RUN node -e "const fs=require('fs'),p=require('path');function walk(d){for(const e of fs.readdirSync(d,{withFileTypes:true})){const f=p.join(d,e.name);if(e.isDirectory())walk(f);else if(e.isFile()&&/^(LICENSE|LICENCE|COPYING|NOTICE)/i.test(e.name)){const t=p.join('/src/third-party-licenses',p.relative('/src/node_modules',f));fs.mkdirSync(p.dirname(t),{recursive:true});fs.copyFileSync(f,t)}}}walk('/src/node_modules')"
FROM node:22-bookworm-slim
ARG SOURCE_REVISION=local
ENV QUANTUM_SOURCE_REVISION=$SOURCE_REVISION
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 HOSTNAME=0.0.0.0 PORT=3000 API_BASE_URL=http://api:8100
WORKDIR /app
COPY --from=build --chown=node:node /src/apps/web/.next/standalone ./
COPY --from=build --chown=node:node /src/apps/web/.next/static ./apps/web/.next/static
COPY --from=build /src/third-party-licenses /app/third-party-licenses
COPY LICENSE NOTICE /app/
USER node
CMD ["node","apps/web/server.js"]
