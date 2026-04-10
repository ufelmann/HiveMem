FROM hivemem-base:latest

USER root
WORKDIR /app
COPY hivemem/ hivemem/
COPY scripts/ scripts/
COPY entrypoint.sh .

RUN cp scripts/hivemem-backup /usr/local/bin/hivemem-backup \
    && chmod +x /usr/local/bin/hivemem-backup

USER postgres
EXPOSE 8421
ENTRYPOINT ["/app/entrypoint.sh"]
