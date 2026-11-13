FROM python:3.12-slim AS runtime
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app
COPY pyproject.toml requirements.lock ./
COPY src ./src
RUN pip install --no-cache-dir -r requirements.lock && pip install --no-cache-dir --no-deps . && useradd --uid 10001 --create-home app
USER app
EXPOSE 8080
CMD ["gunicorn", "--bind", "0.0.0.0:8080", "--workers", "1", "--threads", "4", "--timeout", "90", "openservice.app:create_app()"]

FROM runtime AS test
USER root
RUN pip install --no-cache-dir pytest==9.2.0 coverage==7.17.3
COPY tests /app/tests
COPY scripts /app/scripts
USER app
WORKDIR /tmp
CMD ["sh", "-c", "OPEN_SERVICE_TEST_DATABASE_URL=\"$DATABASE_URL\" python -m coverage run --branch --source=openservice,/app/scripts -m pytest /app/tests -q -p no:cacheprovider && python -m coverage report --fail-under=100"]
