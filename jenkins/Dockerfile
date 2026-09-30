# Jenkins controller for the bi-backend deployment pipeline, run locally with jenkins/compose.yaml.
FROM jenkins/jenkins:lts-jdk21

USER root

# git: check out the repository. jq: build JSON safely in shell. curl/unzip: install the AWS CLI.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip git jq \
    && rm -rf /var/lib/apt/lists/*

# AWS CLI v2 (the pipeline calls ECR and Systems Manager). Picks the right build for Intel or ARM.
RUN ARCH="$(uname -m)" \
    && curl -fsSL "https://awscli.amazonaws.com/awscli-exe-linux-${ARCH}.zip" -o /tmp/awscliv2.zip \
    && unzip -q /tmp/awscliv2.zip -d /tmp \
    && /tmp/aws/install \
    && rm -rf /tmp/aws /tmp/awscliv2.zip

USER jenkins

# Preinstall plugins so a fresh Jenkins is reproducible instead of hand-clicked.
COPY --chown=jenkins:jenkins plugins.txt /usr/share/jenkins/ref/plugins.txt
RUN jenkins-plugin-cli --plugin-file /usr/share/jenkins/ref/plugins.txt
