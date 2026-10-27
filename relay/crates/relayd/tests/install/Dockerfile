# A fresh VPS for the install tests: systemd as PID 1, sshd, and nothing xray needs. unzip and
# nftables are left out on purpose, so `relayd install` has to fetch them.
ARG BASE
FROM ${BASE}
RUN apt-get update -qq \
    && apt-get install -y -qq --no-install-recommends systemd systemd-sysv curl ca-certificates openssh-server python3 jq openssl procps iproute2 \
    && rm -rf /var/lib/apt/lists/* \
    && systemctl mask getty.target console-getty.service systemd-logind.service
# Without container= systemd assumes bare metal and spawns gettys on the host's VTs.
ENV container=docker
STOPSIGNAL SIGRTMIN+3
CMD ["/sbin/init"]
