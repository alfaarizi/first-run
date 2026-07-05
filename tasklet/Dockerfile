# Dev-server image after docker/awesome-compose react-express-mysql frontend.
# compose bind-mounts ./src over /app/src for hot reload.
FROM node:22-alpine
WORKDIR /app
# The bundled npm 10 ships vulnerable sigstore and picomatch, fixed in npm 11.
RUN npm install -g npm@11
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
EXPOSE 5174
CMD ["npm", "run", "dev", "--", "--host"]
