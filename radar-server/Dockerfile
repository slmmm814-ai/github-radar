FROM python:3.12-slim
WORKDIR /app
COPY radar_server.py .
ENV RADAR_DB=/data/radar.db PORT=8787
VOLUME /data
EXPOSE 8787
CMD ["python", "radar_server.py"]
