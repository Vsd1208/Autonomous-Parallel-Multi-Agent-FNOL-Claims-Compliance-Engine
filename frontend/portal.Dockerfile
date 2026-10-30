FROM node:22-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm install
COPY frontend .
# Sample photos for the demo tools (vite.portal.config.ts publicDir = ../../demo-scenarios/sample-images)
COPY demo-scenarios/sample-images /demo-scenarios/sample-images
ARG VITE_API_BASE_URL=http://localhost:8080
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
# The policyholder portal never carries the adjuster token: no build arg for it, and it is cleared here.
ENV VITE_ADJUSTER_TOKEN=
RUN npm run build:portal
FROM node:22-alpine
WORKDIR /app
RUN npm install -g vite
COPY --from=build /app/dist-portal ./dist-portal
EXPOSE 5174
CMD ["vite","preview","--host","0.0.0.0","--port","5174","--outDir","dist-portal"]
