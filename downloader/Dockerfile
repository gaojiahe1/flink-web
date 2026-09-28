FROM python:3.12-slim
RUN pip install --no-cache-dir boto3==1.42.70 && useradd -u 9999 -m downloader
COPY download.py /app/download.py
USER 9999
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
ENTRYPOINT ["python", "/app/download.py"]
