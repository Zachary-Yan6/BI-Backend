# CI/CD for bi-backend: GitHub Actions → Amazon ECR → Jenkins → EC2

This is a hands-on runbook. Work through the phases in order; each one ends with a **checkpoint**
so you know it works before building on it. Placeholders look like `<ACCOUNT_ID>`, so replace them
with your own values and keep a note of each one as you go (there's a worksheet at the end).

---

## 1. The big picture

```
 you ──git push──▶ GitHub ──triggers──▶ GitHub Actions (CI)
                                          1. build + 195 unit tests + 90% coverage gate
                                          2. integration tests (real MySQL/Redis/RabbitMQ)
                                          3. only on main, only if 1–2 passed:
                                             docker build → push  bi-backend:<commit-sha>
                                                               │
                                                               ▼
                                                     Amazon ECR (image registry)
                                                               ▲ checks image exists
 Jenkins on your laptop (CD) ──polls GitHub every 2 min──┐     │
   1. new commit on main? ───────────────────────────────┘     │
   2. wait until CI's image for that commit is in ECR ─────────┘
   3. tell EC2 (via AWS Systems Manager) to run deploy/deploy.sh <sha>
   4. smoke-test the public health URL
                                                               │
                                                               ▼
                                   EC2 instance (Docker Compose): backend + MySQL + Redis + RabbitMQ
                                     deploy.sh: pull image → restart → health check → rollback if unhealthy
```

### Vocabulary

| Term | Meaning here |
|---|---|
| **CI** (Continuous Integration) | Every change is automatically built and tested, so broken code is caught within minutes of being pushed. |
| **CD** (Continuous Delivery/Deployment) | Every change that passes CI can be (delivery) or automatically is (deployment) released. This setup does automatic deployment of `main`. |
| **Artifact** | The thing you ship. Here it is a **Docker image**, built once and deployed unchanged. |
| **Registry** | Storage for images. **ECR** is AWS's private Docker registry. |
| **Immutable tag** | Each image is tagged with its git commit SHA and never overwritten, so "deploy `a1b2c3d`" always means the same bytes. |
| **Quality gate** | A check that must pass before code moves forward: tests, the 90% coverage rule, and branch protection. |
| **IAM** | AWS Identity and Access Management: *who* may do *what* to *which* resource. |
| **OIDC** | OpenID Connect. Lets GitHub prove "I am workflow X of repo Y" to AWS, which issues temporary credentials. No stored AWS keys. |
| **SSM** | AWS Systems Manager. Runs commands on EC2 and opens shells without SSH or open ports. |

### Why the work is split this way

- **CI in GitHub Actions**: it sits next to the code, runs on every pull request, needs no server
  of yours, and its Ubuntu runners come with Docker for Testcontainers.
- **CD in Jenkins**: deployments need to be serialised (one at a time), be auditable, and support
  "redeploy an older version". Jenkins models this well, and it's widely used in industry.
- **The handover is the image in ECR.** CI publishes only after every test passes, so Jenkins just
  waits for the image to exist. A failed CI run publishes nothing, which means a broken commit can
  never be deployed.

### Build once, deploy many

The image is built **once** in CI and the *same* image is deployed. The server never compiles
code. If you rebuilt on the server, you could ship something different from what you tested
(different dependency download, different JDK patch).

---

## 2. What is in the repository

| File | Role |
|---|---|
| `.github/workflows/ci.yml` | The CI pipeline (GitHub Actions). |
| `Dockerfile` | Two-stage image build; runs as a non-root user. |
| `compose.yaml` | Your existing stack definition (used locally and on EC2). |
| `compose.prod.yaml` | Production overrides: use the ECR image, persist uploads, cap memory and logs. |
| `deploy/deploy.sh` | Runs **on EC2**: pull → restart → health check → rollback. |
| `deploy/ec2-user-data.sh` | First-boot script that installs Docker, Compose and git on EC2. |
| `deploy/aws/*.json` | IAM and ECR policies used in the AWS setup steps below. |
| `Jenkinsfile` | The CD pipeline (Jenkins). |
| `jenkins/` | Jenkins image (with AWS CLI + plugins), its compose file, and the SSM deploy script. |
| `sql/migrations/*.sql` | Now **idempotent**: safe to run on every deploy (CI applies them twice to prove it). |

Also changed for the pipeline:
- **Spring Boot Actuator**: `GET /api/actuator/health` returns `{"status":"UP"}` only if the app
  *and* MySQL, Redis and RabbitMQ are reachable. The deploy uses it to decide success or rollback.
  No other actuator endpoint is exposed.
- **`.gitignore`** now ignores `data/` (user uploads must never be committed); **`.gitattributes`**
  forces LF line endings so shell scripts run on Linux.
- Integration tests **skip locally** when Docker is not running, but **fail in CI** (where `CI=true`)
  rather than silently passing without testing anything.

---

## Phase 0: Verify locally

Start Docker Desktop, then:

```bash
./mvnw -B verify
```

**Checkpoint:** `BUILD SUCCESS`, with 195 unit tests and the integration tests all passing (not skipped).

Optional: build the production image the same way CI will:

```bash
docker build -t bi-backend:local .
```

---

## Phase 1: Git and GitHub

### 1.1 Create the repository locally

```bash
git init -b main
git add .
git status
```

**Read the `git status` output before committing.** `.env` (your real passwords and API key) and
`data/` must **not** be listed. If they are, stop and fix `.gitignore` first. Anything pushed to
GitHub should be treated as leaked, even if you delete it later.

Git on Windows doesn't record the Unix "executable" permission. Linux runners and EC2 need it to
run these scripts:

```bash
git update-index --chmod=+x mvnw deploy/deploy.sh deploy/ec2-user-data.sh jenkins/scripts/ssm-deploy.sh
git commit -m "Initial commit with CI/CD pipeline"
```

### 1.2 Push to GitHub

1. On github.com: **New repository** → name `BI-Backend` → **Private** → do *not* add a README.
2. Then:

```bash
git remote add origin https://github.com/<GITHUB_OWNER>/<GITHUB_REPO>.git
git push -u origin main
```

**Checkpoint:** open the repo's **Actions** tab. The `CI` workflow runs. `Build, test and coverage
gate` should pass. `Publish image to Amazon ECR` **will fail**, which is expected because AWS isn't
set up yet.

Open the run to see the **Coverage** table on the summary page and the downloadable
`coverage-reports` artifact.

### 1.3 Protect `main` (after the first CI run)

**Settings → Branches → Add branch ruleset** (or classic "branch protection rule") for `main`:

- Require a pull request before merging.
- Require status checks to pass → add **`Build, test and coverage gate`**.

From now on nothing reaches `main`, and therefore production, without passing CI. Your normal
workflow becomes: branch → push → open PR → CI runs → merge.

---

## Phase 2: AWS account foundations

Do these once. They protect you from surprise bills and from leaking all-powerful credentials.

1. **Don't use the root user** (the email you signed up with) for daily work. Enable MFA on it and
   put it away. Create an everyday admin identity with **IAM Identity Center** (recommended) or an IAM
   user with MFA.
2. **Create a budget:** Billing → **Budgets** → Create → *Monthly cost budget* of, say, $40 with
   email alerts at 80% and 100%.
3. **Pick one region** and use it for everything (for example `eu-west-2` London or `us-east-1`).
   Resources in other regions are invisible from the one you're in, which is a classic source of
   "where did my instance go?" confusion.
4. Note your 12-digit **account ID** (top-right menu).

---

## Phase 3: Let CI publish images (ECR + OIDC)

### 3.1 Create the ECR repository

ECR → **Create repository**:
- Name: `bi-backend`, Private.
- **Tag immutability: Immutable**, so a tag can never be overwritten to point at different code.
- **Scan on push: on** (free basic vulnerability scan of OS packages).

Then open the repository → **Lifecycle policy** → paste `deploy/aws/ecr-lifecycle-policy.json`
(keeps the latest 30 images, which bounds storage cost).

### 3.2 Trust GitHub as an identity provider

IAM → **Identity providers** → Add provider:
- Type **OpenID Connect**, URL `https://token.actions.githubusercontent.com`, audience `sts.amazonaws.com`.

**How OIDC works:** when the `publish-image` job runs, GitHub signs a short-lived token stating
*"repo `owner/name`, branch `main`"*. AWS verifies the signature against this provider and, if the
role's trust policy matches, hands back credentials that expire in about an hour. There is no secret
to steal or rotate.

### 3.3 Create the role CI assumes

IAM → **Roles** → Create role → **Custom trust policy** → paste
`deploy/aws/github-actions-trust-policy.json`, replacing `<ACCOUNT_ID>`, `<GITHUB_OWNER>` and `<GITHUB_REPO>`.

The `sub` condition is the security boundary: only workflows running on **`main` of your repo** can
assume the role, not pull requests, other branches or forks.

Next → skip managed policies → name it `github-actions-bi-backend-ci` → Create. Open the role →
**Add permissions → Create inline policy → JSON** → paste
`deploy/aws/github-actions-ecr-push-policy.json` (fill `<REGION>`, `<ACCOUNT_ID>`). This lets it push
to `bi-backend` and nothing else. Copy the role **ARN**.

### 3.4 Tell the workflow about AWS

GitHub repo → **Settings → Secrets and variables → Actions → Variables** tab → add:

| Name | Value |
|---|---|
| `AWS_REGION` | e.g. `eu-west-2` |
| `AWS_CI_ROLE_ARN` | `arn:aws:iam::<ACCOUNT_ID>:role/github-actions-bi-backend-ci` |

These are **variables**, not secrets: they identify things but grant nothing on their own.

**Checkpoint:** Actions → the failed run → **Re-run all jobs**. `Publish image` goes green, and
ECR → `bi-backend` shows an image tagged with the 40-character commit SHA.

---

## Phase 4: The EC2 host

### 4.1 Instance role

IAM → Roles → Create role → trusted entity **AWS service → EC2** → attach managed policies:
- `AmazonSSMManagedInstanceCore`: lets Systems Manager manage the instance (Session Manager, Run Command).
- `AmazonEC2ContainerRegistryReadOnly`: lets the instance pull images.

Name: `bi-backend-ec2`. The instance gets temporary credentials automatically; no keys are ever
stored on the server.

### 4.2 Security group (the instance firewall)

EC2 → Security Groups → Create `bi-backend-sg`:
- Inbound: **Custom TCP 8101** from **My IP** while testing (switch to `0.0.0.0/0` when the frontend needs it).
- **No port 22.** You'll use Session Manager instead of SSH.
- Outbound: leave "all traffic" (needed for ECR, GitHub, the AI API and SSM).

MySQL, Redis and RabbitMQ are bound to `127.0.0.1` in `compose.yaml`, so they're unreachable from outside
even though they run on this host.

### 4.3 Launch the instance

EC2 → **Launch instance**:

| Setting | Value | Why |
|---|---|---|
| AMI | Amazon Linux 2023 | Has the SSM agent and AWS CLI preinstalled. |
| Type | **t3.medium** (4 GB) | JVM (1 GB cap) + MySQL + RabbitMQ + Redis need about 2.5 GB. `t3.small` (2 GB) works only thanks to swap and will be slow. |
| Key pair | **Proceed without a key pair** | No SSH, so there's no key to lose. |
| Network | Existing `bi-backend-sg` | |
| Storage | 30 GiB gp3 | Docker images, MySQL data, logs. |
| Advanced → IAM instance profile | `bi-backend-ec2` | |
| Advanced → Metadata version | V2 only | `deploy.sh` uses IMDSv2. |
| Advanced → **User data** | paste `deploy/ec2-user-data.sh` | Installs Docker, Compose, git and swap on first boot. |

Then EC2 → **Elastic IPs** → Allocate → Associate with the instance. This gives it a public IP that
survives stop/start. Note the **instance ID** (`i-...`) and the **Elastic IP**.

### 4.4 Connect and prepare the app directory

Wait about 3 minutes, then select the instance → **Connect → Session Manager → Connect**. You get a
browser shell as `ssm-user`; switch to the app user:

```bash
sudo su - ec2-user
docker version && docker compose version    # proves user data finished
```

(If Docker is missing, check `sudo tail -50 /var/log/cloud-init-output.log`.)

The server needs **read-only** access to your private repo. Create a *deploy key*:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/github_deploy -N "" -C "bi-ec2-deploy"
cat ~/.ssh/github_deploy.pub
```

GitHub → repo → **Settings → Deploy keys → Add** → paste it → leave **write access unchecked**. Then:

```bash
cat >> ~/.ssh/config <<'EOF'
Host github.com
  IdentityFile ~/.ssh/github_deploy
EOF
chmod 600 ~/.ssh/config
ssh-keyscan github.com >> ~/.ssh/known_hosts
git clone git@github.com:<GITHUB_OWNER>/<GITHUB_REPO>.git /opt/bi/app
```

### 4.5 Production secrets

Secrets live only on the server, readable only by `ec2-user`, and never in git:

```bash
cd /opt/bi/app
cat > .env <<EOF
MYSQL_ROOT_PASSWORD=$(openssl rand -hex 24)
RABBITMQ_USERNAME=bi
RABBITMQ_PASSWORD=$(openssl rand -hex 24)
DEEPSEEK_API_KEY=<your DeepSeek key>
EOF
chmod 600 .env
```

`openssl rand -hex` generates strong passwords with no special characters that could break shell
or YAML quoting.

### 4.6 First deployment, by hand

Deploy manually once before automating it, so you can see every step:

```bash
cd /opt/bi/app
./deploy/deploy.sh "$(git rev-parse HEAD)"
```

The first run takes a few minutes (pulling images, initialising MySQL). You should end with
`Release xxxxxxx is healthy.`

**Checkpoint (from your laptop):** `http://<ELASTIC_IP>:8101/api/actuator/health` returns
`{"status":"UP"}`, and `http://<ELASTIC_IP>:8101/api/swagger-ui.html` loads.

---

## Phase 5: Jenkins on your laptop

### 5.1 An IAM user for Jenkins

Jenkins runs outside AWS, so unlike CI (OIDC) and EC2 (instance role) it needs an **access key**. This
is the one long-lived credential in the system, so it gets the narrowest possible permissions.

IAM → Users → Create user `jenkins-bi-deployer`, **no console access** → Create inline policy →
paste `deploy/aws/jenkins-deployer-policy.json` (fill `<REGION>`, `<ACCOUNT_ID>`, `<INSTANCE_ID>`).
It can only check for images in `bi-backend`, and run commands on **this one instance**.

User → **Security credentials → Create access key** → "Application running outside AWS". Copy both
values now, because the secret is shown only once. Never commit it or paste it anywhere else.

### 5.2 Start Jenkins

```bash
docker compose -f jenkins/compose.yaml up -d --build
docker compose -f jenkins/compose.yaml exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

Open http://localhost:8080 → paste the password → **Install suggested plugins** (ours are already
baked in) → create your admin user.

### 5.3 Configure Jenkins

**Credentials** (Manage Jenkins → Credentials → System → Global → Add credentials):

| Kind | ID | Contents |
|---|---|---|
| AWS Credentials | `aws-bi-deployer` | the access key from 5.1 |
| Username with password | `github-readonly` | your GitHub username + a **fine-grained personal access token** limited to this repo with *Contents: Read-only* (GitHub → Settings → Developer settings) |

**Global environment variables** (Manage Jenkins → System → Global properties → ☑ Environment variables):

| Name | Value |
|---|---|
| `AWS_REGION` | your region |
| `BI_INSTANCE_ID` | `i-...` |
| `BI_PUBLIC_URL` | `http://<ELASTIC_IP>:8101` |

### 5.4 Create the pipeline job

**New Item** → name `bi-backend-deploy` → **Pipeline** → OK → in **Pipeline**:
- Definition: **Pipeline script from SCM** → SCM **Git**
- Repository URL `https://github.com/<GITHUB_OWNER>/<GITHUB_REPO>.git`, credentials `github-readonly`
- Branch `*/main`, Script Path `Jenkinsfile` → Save.

Click **Build Now** once. Jenkins only learns the `pollSCM` trigger and the `RELEASE_SHA` parameter
after it has read the Jenkinsfile, so this first run also "installs" the job's configuration. It
deploys the current `main`.

**Checkpoint:** all four stages go green in the Stage View. EC2 → Systems Manager → **Run Command →
Command history** shows the command Jenkins sent, with its full output.

---

## Phase 6: The whole loop

1. `git switch -c feature/hello`, make a visible change, push, open a PR.
2. CI runs `Build, test and coverage gate` on the PR. Merging is blocked until it's green.
3. Merge. CI runs on `main`, then publishes `bi-backend:<merge-sha>`.
4. Within about 2 minutes Jenkins polls, sees the new commit, and starts a build. It waits in **Wait
   for CI image** until step 3 finishes, then deploys and smoke-tests.

To see the safety net work, merge a change that breaks startup (for example an invalid required
property). `deploy.sh` sees the health check fail, prints the logs, and **rolls back to the previous
image**. Jenkins goes red while production keeps serving the last good release.

---

## Day-2 operations

**Roll back deliberately:** Jenkins → `bi-backend-deploy` → **Build with Parameters** →
`RELEASE_SHA` = the full SHA of an earlier good commit. (Its image still exists: the lifecycle policy
keeps the last 30.)

**Logs on the server** (Session Manager, then `sudo su - ec2-user`, `cd /opt/bi/app`):

```bash
docker compose -f compose.yaml -f compose.prod.yaml --profile app ps
docker compose -f compose.yaml -f compose.prod.yaml --profile app logs -f --tail 100 backend
```

**Migrations rule:** migrations run on *every* deploy and roll *forward only* (a rollback restores
the old image but not the old schema). So every migration must be:
- **idempotent**: CI enforces this by applying all of them twice;
- **backward compatible**: add columns and tables; don't rename or drop something the previous
  release still reads. Remove it in a later release, after nothing uses it ("expand, then contract").

### Troubleshooting

| Symptom | Likely cause |
|---|---|
| CI: `./mvnw: Permission denied` | The executable bit wasn't committed. Run the `git update-index --chmod=+x` step from 1.1. |
| CI: `Could not load credentials` / `Not authorized to perform sts:AssumeRoleWithWebIdentity` | The trust policy `sub` doesn't match `repo:owner/name:ref:refs/heads/main` exactly (case matters), or the `AWS_CI_ROLE_ARN` variable is wrong. |
| CI: push fails with `tag invalid: already exists` | Immutable tags working as intended: that commit was already published. Re-running an old commit's publish job is unnecessary. |
| Jenkins stuck in "Wait for CI image" | CI failed or hasn't finished for that commit. Check the Actions tab. It times out after 25 minutes. |
| Jenkins: `AccessDenied ... ssm:SendCommand` | The policy's `<INSTANCE_ID>`/`<REGION>` doesn't match, or `BI_INSTANCE_ID` is wrong. |
| SSM command `Undeliverable` / instance missing in Fleet Manager | The instance lacks the `bi-backend-ec2` role, or has no outbound internet. |
| Deploy: `BACKEND_IMAGE must be set` | `compose.prod.yaml` was used without `deploy.sh`. Always deploy through the script. |
| Health never `UP` | `docker compose ... logs backend`. Usually a wrong `.env` value or low memory (`free -m`). |
| Smoke test fails but deploy succeeded | The security group doesn't allow your current IP on 8101 (home IPs change). |

---

## Cost and clean-up

Rough monthly on-demand prices (check the AWS pricing pages for your region):

| Item | ~USD/month |
|---|---|
| EC2 t3.medium, running 24/7 | 30–35 |
| EBS 30 GB gp3 | 2.5 |
| Public IPv4 / Elastic IP | 3.6 |
| ECR storage (a few GB) | < 1 |
| Systems Manager, IAM, GitHub Actions (private repos include free minutes) | 0 |

**Stop** the instance when you're not using it to cut the EC2 charge (storage and IP still bill).
To remove everything: terminate the instance, release the Elastic IP, delete the ECR repository,
the IAM roles, the user and its access key, the OIDC provider and the security group.

---

## What to learn next

1. **HTTPS**: put Nginx/Caddy or an Application Load Balancer in front with a free certificate; stop exposing 8101.
2. **Managed data**: move MySQL to RDS (automated backups, point-in-time restore).
3. **Secrets Manager / Parameter Store** instead of a `.env` file on disk.
4. **Infrastructure as Code**: recreate Phases 3–4 with Terraform or AWS CDK so the environment is reproducible.
5. **Staging environment**: deploy to staging automatically, promote to production with a manual Jenkins approval (`input` step).
6. **Supply-chain hardening**: pin GitHub Actions to commit SHAs, add Dependabot, fail CI on critical ECR scan findings.

---

## Worksheet

| Value | Where it's used | Yours |
|---|---|---|
| `<ACCOUNT_ID>` | policies, role ARNs | |
| `<REGION>` | policies, GitHub variable, Jenkins env | |
| `<GITHUB_OWNER>/<GITHUB_REPO>` | trust policy, clone URLs | |
| CI role ARN | GitHub variable `AWS_CI_ROLE_ARN` | |
| `<INSTANCE_ID>` | Jenkins policy, `BI_INSTANCE_ID` | |
| Elastic IP | security group testing, `BI_PUBLIC_URL` | |
