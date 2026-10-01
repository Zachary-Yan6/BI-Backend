// Continuous Delivery for bi-backend: deploys commits on main to the EC2 host.
//
// Division of labour:
//   GitHub Actions (CI) : test every commit; publish an image to ECR only if everything passes.
//   Jenkins (CD)        : notice new commits, wait for CI's image, deploy it, verify it.
//
// Jenkins configuration this file expects (see docs/CI-CD.md):
//   Credential  aws-bi-deployer  (type "AWS Credentials") for the least-privilege IAM user
//   Global environment variables: AWS_REGION, BI_INSTANCE_ID, BI_PUBLIC_URL

pipeline {
    agent any

    options {
        // Two overlapping deployments to one server would fight each other.
        disableConcurrentBuilds()
        timeout(time: 40, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '30'))
        timestamps()
    }

    triggers {
        // Check GitHub for new commits every ~2 minutes. Polling is used instead of a GitHub webhook
        // because this Jenkins runs on a laptop that GitHub cannot reach (and must not be exposed).
        pollSCM('H/2 * * * *')
    }

    parameters {
        string(
            name: 'RELEASE_SHA',
            defaultValue: '',
            description: 'Leave empty to deploy the commit Jenkins just checked out (latest main). ' +
                         'Enter an older full commit SHA to roll back to that release.'
        )
    }

    stages {
        stage('Resolve release') {
            steps {
                script {
                    env.RELEASE_SHA = params.RELEASE_SHA?.trim() ?: sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                    // Plain java.lang.String methods only: the Jenkins script sandbox rejects many Groovy extensions.
                    if (!env.RELEASE_SHA.matches('[0-9a-f]{40}')) {
                        error "RELEASE_SHA must be a full 40-character commit SHA, got '${env.RELEASE_SHA}'"
                    }
                    currentBuild.displayName = "#${env.BUILD_NUMBER} ${env.RELEASE_SHA.substring(0, 7)}"
                    echo "Release candidate: ${env.RELEASE_SHA}"
                }
            }
        }

        stage('Wait for CI image') {
            // The image only exists if every CI check passed. If CI fails, nothing appears and this stage
            // times out, so a broken commit can never be deployed.
            steps {
                withCredentials([aws(credentialsId: 'aws-bi-deployer',
                                     accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                                     secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    timeout(time: 25, unit: 'MINUTES') {
                        // Declarative "steps" accept only steps, not expressions such as "sh(...) == 0".
                        // A script block allows the Groovy needed to return true/false to waitUntil.
                        script {
                            waitUntil(initialRecurrencePeriod: 15000, quiet: true) {
                                def status = sh(returnStatus: true, script: '''
                                    aws ecr describe-images --region "$AWS_REGION" \
                                        --repository-name bi-backend \
                                        --image-ids imageTag="$RELEASE_SHA" >/dev/null 2>&1
                                ''')
                                return status == 0
                            }
                        }
                    }
                }
                echo "CI image bi-backend:${env.RELEASE_SHA} is available in ECR."
            }
        }

        stage('Deploy to EC2') {
            steps {
                withCredentials([aws(credentialsId: 'aws-bi-deployer',
                                     accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                                     secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                    sh 'bash jenkins/scripts/ssm-deploy.sh'
                }
            }
        }

        stage('Smoke test') {
            // deploy.sh already checked health from inside the server. This checks from outside, which also
            // proves the security group, public IP and port mapping are right.
            steps {
                sh '''
                    curl -fsS --retry 6 --retry-delay 5 --retry-all-errors --max-time 10 \
                        "$BI_PUBLIC_URL/api/actuator/health" | tee /dev/stderr | grep -q '"status":"UP"'
                '''
            }
        }
    }

    post {
        success {
            echo "Deployed ${env.RELEASE_SHA} to ${env.BI_PUBLIC_URL}"
        }
        failure {
            echo 'Deployment failed. If the new release was unhealthy, deploy.sh has already rolled back to the previous image; see the remote output above.'
        }
    }
}
