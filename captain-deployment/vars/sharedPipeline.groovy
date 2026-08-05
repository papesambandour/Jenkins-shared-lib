// File: captain-deployment/vars/sharedPipeline.groovy
// Place this file in your Jenkins shared library

def call(Map config) {
    // Default configuration with overridable parameters
    def appName = config.appName ?: 'default-app'
    // La branche N'EST PAS résolue ici : env.GIT_BRANCH n'est peuplé qu'après le
    // stage "Declarative: Checkout SCM", donc après l'évaluation de ce corps de
    // méthode. La résoudre trop tôt donnait "caprover deploy -b null".
    def configuredBranch = config.gitBranch
    def resolvedBranch = null
    def notifyEmails = config.notifyEmails ?: env.NOTIFY_EMAIL_DEFAULT
    def captainUrl = config.captainUrl ?: env.CAPTAIN_URL
    def captainPassword = config.captainPassword ?: env.PASSWORD_CAPROVER
    def deploymentTimeout = config.deploymentTimeout ?: '300'
    def fromEmail = config.fromEmail ?: env.FROM_MAIL
    // Image d'exécution + version du CLI surchargeables : une nouvelle release
    // caprover ne peut plus casser tous les pipelines d'un coup.
    def dockerImage = config.dockerImage ?: 'papesambandour/docker-node-alpine-16-git:1.1'
    def caproverVersion = config.caproverVersion ?: '2.3.1'

    // Validate required parameters
    if (!captainUrl || !captainPassword) {
        error "🚨 Missing required deployment credentials: captainUrl or captainPassword"
    }

    pipeline {
        agent {
            docker {
                image "${dockerImage}"
                args '-u root:root'
            }
        }

        options {
            timeout(time: 30, unit: 'MINUTES')
            disableConcurrentBuilds()
        }

        environment {
            CAPROVER_VERSION = "${caproverVersion}"
        }

        stages {
            stage('Setup') {
                steps {
                    echo "Starting pipeline for ${appName} deployment"
                    sh 'node --version && npm --version'
                    sh 'git --version'
                    // Le conteneur tourne en root sur un workspace appartenant à uid 1000
                    sh 'git config --global --add safe.directory "*" || true'
                }
            }

            stage('Install CapRover CLI') {
                steps {
                    echo "Installing CapRover CLI ${caproverVersion}"
                    sh '''
                        set -e
                        NODE_MAJOR=$(node -p "process.versions.node.split('.')[0]")
                        echo "Node major version detected: ${NODE_MAJOR}"

                        # caprover >= 2.4.0 exige Node >= 20 et plante en ERR_REQUIRE_ESM
                        # sur un runtime plus ancien : on epingle donc la version.
                        if [ "${NODE_MAJOR}" -lt 20 ] && [ "${CAPROVER_VERSION}" = "latest" ]; then
                            echo "Node ${NODE_MAJOR} cannot run caprover@latest, falling back to 2.3.1"
                            CAPROVER_VERSION=2.3.1
                        fi

                        npm install -g --no-fund --no-audit "caprover@${CAPROVER_VERSION}"
                    '''
                    sh '''
                        set -e
                        if ! caprover --version; then
                            echo "CapRover CLI installe mais non executable sur ce runtime Node."
                            echo "Epinglez caproverVersion sur une release compatible, ou passez"
                            echo "dockerImage sur une image Node >= 20 dans votre Jenkinsfile."
                            exit 1
                        fi
                    '''
                }
            }

            stage('Prepare Deployment') {
                steps {
                    script {
                        // Résolution au runtime : ici le checkout SCM a eu lieu, GIT_BRANCH existe.
                        resolvedBranch = normalizeBranch(configuredBranch ?: env.GIT_BRANCH ?: env.BRANCH_NAME)
                        if (!resolvedBranch) {
                            error "🚨 Branche git non résolue (GIT_BRANCH et BRANCH_NAME vides) : passez gitBranch: '<branche>' à sharedPipeline()"
                        }
                        echo "Preparing deployment for branch: ${resolvedBranch}"
                        if (!captainUrl || !captainPassword) {
                            error "🚨 Missing required deployment credentials"
                        }
                    }
                }
            }

            stage('Deploy to CapRover') {
                steps {
                    script {
                        echo "Deploying ${appName} from branch ${resolvedBranch} to ${captainUrl}"
                        // Le mot de passe passe par l'environnement au lieu d'être interpolé
                        // dans le script shell : il ne se retrouve ni dans le log, ni dans le
                        // fichier de script temporaire écrit sur le disque de l'agent.
                        withEnv(["CAPROVER_PASSWORD=${captainPassword}"]) {
                            sh """
                                set +x
                                caprover deploy \
                                    -h ${captainUrl} \
                                    -b ${resolvedBranch} \
                                    -a ${appName}
                            """
                        }
                    }
                }
            }

        }

        post {
            success {
                script {
                    def recipients = buildRecipients(notifyEmails)
                    def branchLabel = resolvedBranch ?: 'unknown'
                    if (recipients) {
                        emailext(
                                subject: "✅ SUCCESSFUL: ${appName} Deployment to CapRover",
                                body: """
                            <h2>Deployment Successful</h2>
                            <p>The ${appName} application was successfully deployed from branch <b>${branchLabel}</b>.</p>
                            <p><b>Build URL:</b> <a href="${BUILD_URL}">${BUILD_URL}</a></p>
                            <p><b>Build Number:</b> ${BUILD_NUMBER}</p>
                            <p><b>Completed:</b> ${new Date()}</p>
                        """,
                                mimeType: 'text/html',
                                replyTo: "${fromEmail}",
                                to: recipients,
                                attachLog: true,
                                from: "${fromEmail}"
                        )
                    } else {
                        echo "No notification recipient configured, skipping success email"
                    }
                }
            }

            failure {
                script {
                    def recipients = buildRecipients(notifyEmails)
                    // Un échec avant "Prepare Deployment" (ex : install du CLI) laisse
                    // resolvedBranch à null : on retente une résolution pour le mail.
                    def branchLabel = resolvedBranch ?:
                            normalizeBranch(configuredBranch ?: env.GIT_BRANCH ?: env.BRANCH_NAME) ?: 'unknown'
                    if (recipients) {
                        emailext(
                                subject: "❌ FAILED: ${appName} Deployment to CapRover",
                                body: """
                            <h2>Deployment Failed</h2>
                            <p>The ${appName} application deployment from branch <b>${branchLabel}</b> has failed.</p>
                            <p><b>Build URL:</b> <a href="${BUILD_URL}">${BUILD_URL}</a></p>
                            <p><b>Build Number:</b> ${BUILD_NUMBER}</p>
                            <p><b>Failed At:</b> ${new Date()}</p>
                            <p>Please check the attached log for details.</p>
                        """,
                                mimeType: 'text/html',
                                replyTo: "${fromEmail}",
                                to: recipients,
                                attachLog: true,
                                compressLog: true,
                                from: "${fromEmail}"
                        )
                    } else {
                        echo "No notification recipient configured, skipping failure email"
                    }
                }
            }

            always {
                cleanWs()
            }
        }
    }
}

// Normalise le nom de branche fourni par Jenkins.
// GIT_BRANCH vaut souvent "origin/release" ou "refs/heads/release" ; le CLI caprover
// attend un nom de branche git local ("release"), sinon le deploy échoue.
// Retourne null si rien d'exploitable, pour que l'appelant puisse échouer proprement.
def normalizeBranch(raw) {
    if (!raw) {
        return null
    }
    def branch = raw.toString().trim()
    if (branch.isEmpty() || branch == 'null') {
        return null
    }
    branch = branch.replaceFirst(/^refs\/heads\//, '')
    branch = branch.replaceFirst(/^refs\/remotes\//, '')
    branch = branch.replaceFirst(/^origin\//, '')
    return branch.isEmpty() ? null : branch
}

// Transforme "a@x.com;b@y.com" en "<a@x.com>, <b@y.com>", chaîne vide si rien d'exploitable
def buildRecipients(emails) {
    if (!emails || emails.toString().trim().isEmpty() || emails.toString() == 'null') {
        return ''
    }
    return emails.toString()
            .split(';')
            .findAll { it.trim() }
            .collect { "<${it.trim()}>" }
            .join(', ')
}
