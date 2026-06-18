FROM rust:1-bookworm AS builder

WORKDIR /app

COPY Cargo.toml Cargo.lock ./
COPY src ./src

RUN cargo build --locked --release

FROM python:3.12-slim-bookworm AS runtime

ENV PYTHONUNBUFFERED=1 \
    PATH="/opt/venv/bin:${PATH}" \
    PORT=8000

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        libgomp1 \
    && rm -rf /var/lib/apt/lists/*

RUN python -m venv /opt/venv

ARG DOCLING_VERSION
ARG PYTORCH_CPU_INDEX_URL=https://download.pytorch.org/whl/cpu
RUN python -m pip install --upgrade pip \
    && python -m pip install --no-cache-dir --index-url "$PYTORCH_CPU_INDEX_URL" torch torchvision \
    && if [ -n "$DOCLING_VERSION" ]; then \
        python -m pip install --no-cache-dir "docling==$DOCLING_VERSION"; \
    else \
        python -m pip install --no-cache-dir docling; \
    fi \
    && docling --version

COPY --from=builder /app/target/release/api /usr/local/bin/api

RUN useradd --system --create-home --shell /usr/sbin/nologin appuser

USER appuser
EXPOSE 8000

CMD ["api"]
