def jenkinsfile

// Testene er avhengig av at cluster er satt til utv. Ellers feiler testene med "Invalid cluster configuration"
env.OPENSHIFT_CLUSTER = "utv"

def overrides = [
    scriptVersion  : 'v7',
    iqOrganizationName: "Team AOS",
    iqBreakOnUnstable: true,
    iqEmbedded: true,
    compilePropertiesIq: "-x test",
    pipelineScript: 'https://git.aurora.skead.no/scm/ao/aurora-pipeline-scripts.git',
    credentialsId: "github",
    checkstyle : false,
    javaVersion: 17,
    jiraFiksetIKomponentversjon: true,
    chatRoom: "#aos-notifications",
    versionStrategy: [
      [branch: 'master', versionHint: '3'],
      [branch: 'release/v2', versionHint: '2'],
    ],
    uploadLeveransepakke: true
]

fileLoader.withGit(overrides.pipelineScript,, overrides.scriptVersion) {
   jenkinsfile = fileLoader.load('templates/leveransepakke')
}

jenkinsfile.gradle(overrides.scriptVersion, overrides)
