// File: jenkins/sharedPipeline.groovy
// Place this file in your Jenkins shared library

def call(Map config) {
    // Default configuration with overridable parameters
    def appName = config.appName ?: 'default-app'
    def gitBranch = config.gitBranch ?: env.GIT_BRANCH
    def notifyEmails = config.notifyEmails ?: env.NOTIFY_PRIME_TEAM_LEAD
    def captainUrl = config.captainUrl ?: env.CAPTAIN_URL_TEST
    def captainPassword = config.captainPassword ?: env.PASSWORD_CAPROVER_TEST
    def deploymentTimeout = config.deploymentTimeout ?: '300'
    def fromEmail = config.fromEmail ?: env.FROM_MAIL
// Validate required parameters
    if (!captainUrl || !captainPassword) {
        error "Missing required deployment credentials: captainUrl or captainPassword"
    }

    pipeline {
        agent {
            docker {
                image 'papesambandour/docker-node-alpine-16-git:1.1'
                args '-u root:root'
            }
        }

        options {
            timeout(time: 30, unit: 'MINUTES')
            disableConcurrentBuilds()
        }


        stages {
            stage('Setup') {
                steps {
                    echo "Starting pipeline for ${appName} deployment"
                    sh 'node --version && npm --version'
                    sh 'git --version'
                }
            }

            stage('Install CapRover CLI') {
                steps {
                    echo "Installing CapRover CLI"
                    sh 'npm install -g caprover'
                    sh 'caprover --version'
                }
            }

            stage('Prepare Deployment') {
                steps {
                    echo "Preparing deployment for branch: $GIT_BRANCH"
                    script {
                        if (!captainUrl || !captainPassword) {
                            error "Missing required deployment credentials"
                        }
                    }
                }
            }

            stage('Deploy to CapRover') {
                steps {
                    echo "Deploying ${appName} from branch- $GIT_BRANCH to ${captainUrl}"
                    sh """
                        set +x  # Disable command echo
                        caprover deploy \
                            -h ${captainUrl} \
                            -p ${captainPassword} \
                            -b $GIT_BRANCH \
                            -a ${appName} 
                        set -x  # Re-enable command echo
                    """
                }
            }

            stage('Verify Deployment') {
                steps {
                    echo "Verifying deployment of ${appName} to ${captainUrl}"
                    sh """
                set +x  # Disable command echo
                caprover status \
                    -h ${captainUrl} \
                    -p ${captainPassword} \
                    -a ${appName} 
                set -x  # Re-enable command echo
            """
                    // Check the exit code of the previous command
                    script {
                        def exitCode = sh(returnStatus: true, script: 'caprover status -h ${captainUrl} -p ${captainPassword} -a ${appName}').exitValue
                        if (exitCode != 0) {
                            error "Deployment verification failed"
                        }
                    }
                }
            }

        }

        post {
            success {
                script {
                    def recipients = "${notifyEmails}".split(';').collect { "<${it.trim()}>" }.join(', ')
                    emailext (
                            subject: "✅ SUCCESSFUL: ${appName} Deployment to CapRover",
                            body: """
                            <h2>Deployment Successful</h2>
                            <p>The ${appName} application was successfully deployed from branch <b>${GIT_BRANCH}</b>.</p>
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
                }
            }

            failure {
                script {
                    def recipients = "${notifyEmails}".split(';').collect { "<${it.trim()}>" }.join(', ')
                    emailext (
                            subject: "❌ FAILED: ${appName} Deployment to CapRover",
                            body: """
                            <h2>Deployment Failed</h2>
                            <p>The ${appName} application deployment from branch <b>${GIT_BRANCH}</b> has failed.</p>
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
                }
            }

            always {
                cleanWs()
            }
        }
    }
}