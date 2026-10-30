FROM node:22-alpine AS build
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm install
COPY frontend .
# Sample photos, served as static files (vite.config.ts publicDir = ../demo-scenarios/sample-images)
COPY demo-scenarios/sample-images /demo-scenarios/sample-images
ARG VITE_API_BASE_URL=http://localhost:8080
ARG VITE_ADJUSTER_TOKEN=
# Old upload links that land on the workspace are sent on to the policyholder portal
ARG VITE_PORTAL_URL=http://localhost:5174
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
ENV VITE_ADJUSTER_TOKEN=$VITE_ADJUSTER_TOKEN
ENV VITE_PORTAL_URL=$VITE_PORTAL_URL
RUN npm run build
FROM node:22-alpine
WORKDIR /app
RUN npm install -g vite
COPY --from=build /app/dist ./dist
EXPOSE 5173
CMD ["vite","preview","--host","0.0.0.0","--port","5173"]
