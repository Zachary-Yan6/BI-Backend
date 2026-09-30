#!/bin/bash
#
# EC2 "user data": AWS runs this once, as root, the first time the instance boots.
# Paste it into Advanced details > User data when launching an Amazon Linux 2023 instance.
# Progress log on the instance: /var/log/cloud-init-output.log
#
# It installs the runtime (Docker, Compose, git), adds swap, and prepares /opt/bi.
# It deliberately does NOT clone the repository or write secrets; you do that once by hand.

set -euxo pipefail

dnf update -y
dnf install -y docker git

# Docker Compose v2 is not packaged for Amazon Linux 2023, so install the official CLI plugin.
mkdir -p /usr/local/lib/docker/cli-plugins
curl -fsSL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-$(uname -m)" \
    -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

systemctl enable --now docker
# Lets ec2-user run docker without sudo (takes effect on that user's next login session).
usermod -aG docker ec2-user

# 2 GB swap as a safety net: MySQL + RabbitMQ + Redis + the JVM share this one machine.
if [ ! -f /swapfile ]; then
    dd if=/dev/zero of=/swapfile bs=1M count=2048
    chmod 600 /swapfile
    mkswap /swapfile
    swapon /swapfile
    echo '/swapfile swap swap defaults 0 0' >> /etc/fstab
fi

mkdir -p /opt/bi/state
chown -R ec2-user:ec2-user /opt/bi
