FROM hivemem-base:latest

WORKDIR /app
COPY hivemem/ hivemem/
COPY scripts/ scripts/
COPY entrypoint.sh .

RUN cp scripts/hivemem-backup /usr/local/bin/hivemem-backup \
    && chmod +x /usr/local/bin/hivemem-backup

EXPOSE 8421
ENTRYPOINT ["/app/entrypoint.sh"]
