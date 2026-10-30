FROM node:22-slim
WORKDIR /app
COPY . .
ENV DB_PATH=/data/data.db
VOLUME /data
EXPOSE 3000
CMD ["node", "--disable-warning=ExperimentalWarning", "server.js"]
