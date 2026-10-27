# A relay host for the live tests: Debian with systemd as PID 1, so the path unit, the
# privileged oneshot and xray.service run as they do on a VPS.
FROM debian:trixie-slim@sha256:a29215f6a35e51e22adffa17f89e9d2ef06214e64a2bad10d765c46aea49f11f
RUN apt-get update -qq \
    && apt-get install -y -qq --no-install-recommends systemd systemd-sysv curl nginx openssl procps \
    && rm -rf /var/lib/apt/lists/* \
    && systemctl mask getty.target console-getty.service systemd-logind.service
# Without container= systemd assumes bare metal and spawns gettys on the host's VTs.
ENV container=docker
STOPSIGNAL SIGRTMIN+3
CMD ["/sbin/init"]
