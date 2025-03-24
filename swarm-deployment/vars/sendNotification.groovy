#!/usr/bin/env groovy

def call(Map params) {
    def status = params.status
    def serviceAppName = params.serviceAppName
    def notifyEmails = params.notifyEmails
    def fromEmail = params.fromEmail
    def buildUrl = params.buildUrl ?: env.BUILD_URL
    def buildNumber = params.buildNumber ?: env.BUILD_NUMBER
    def gitBranch = params.gitBranch ?: env.GIT_BRANCH

    def recipients = "${notifyEmails}".split(';').collect { "<${it.trim()}>" }.join(', ')

    if (status == 'success') {
        emailext (
                subject: "✅ SUCCESSFUL: ${serviceAppName} Deployment to CapRover",
                body: """
                <h2>Deployment Successful</h2>
                <p>The ${serviceAppName} application was successfully deployed from branch <b>${gitBranch}</b>.</p>
                <p><b>Build URL:</b> <a href="${buildUrl}">${buildUrl}</a></p>
                <p><b>Build Number:</b> ${buildNumber}</p>
                <p><b>Completed:</b> ${new Date()}</p>
            """,
                mimeType: 'text/html',
                replyTo: "${fromEmail}",
                to: recipients,
                attachLog: true,
                from: "${fromEmail}"
        )
    } else {
        emailext (
                subject: "❌ FAILED: ${serviceAppName} Deployment to CapRover",
                body: """
                <h2>Deployment Failed</h2>
                <p>The ${serviceAppName} application deployment from branch <b>${gitBranch}</b> has failed.</p>
                <p><b>Build URL:</b> <a href="${buildUrl}">${buildUrl}</a></p>
                <p><b>Build Number:</b> ${buildNumber}</p>
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