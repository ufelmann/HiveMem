ARG BASE_IMAGE=hivemem-base:latest
FROM ${BASE_IMAGE}

USER root
WORKDIR /app
COPY hivemem/ hivemem/
COPY migrations/ migrations/
COPY scripts/ scripts/
COPY entrypoint.sh .

RUN cp scripts/hivemem-backup /usr/local/bin/hivemem-backup \
    && cp scripts/hivemem-token /usr/local/bin/hivemem-token \
    && chmod +x /usr/local/bin/hivemem-backup \
    && chmod +x /usr/local/bin/hivemem-token \
    && chmod +x scripts/hivemem-migrate \
    && mkdir -p /data/models && chown postgres:postgres /data/models

USER postgres
EXPOSE 8421
ENTRYPOINT ["/app/entrypoint.sh"]
