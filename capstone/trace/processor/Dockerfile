
FROM python:3.12-slim
WORKDIR /app

COPY processor/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY processor/ .

RUN useradd --system --uid 10001 trace
USER trace

EXPOSE 8000

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
