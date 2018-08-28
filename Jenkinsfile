def jenkinsfile


def overrides = [
    scriptVersion  : 'feature/AOS-2708',
    pipelineScript: 'https://git.aurora.skead.no/scm/ao/aurora-pipeline-scripts.git',
    piTests: false,
    checkstyle: false,
    docs: false,
    sonarqube: false,
    credentialsId: "github"
]

fileLoader.withGit(overrides.pipelineScript,, overrides.pipelineScript) {
   jenkinsfile = fileLoader.load('templates/leveransepakke')
}
jenkinsfile.gradle(overrides.scriptVersion, overrides)
