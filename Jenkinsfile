def jenkinsfile

def scriptVersion  = 'feature/AOS-2708'
fileLoader.withGit('https://git.aurora.skead.no/scm/ao/aurora-pipeline-scripts.git', scriptVersion) {
   jenkinsfile = fileLoader.load('templates/leveransepakke')
}

def overrides = [
    piTests: false,
    checkstyle: false,
    docs: false,
    sonarqube: false,
    credentialsId: "github"
]

jenkinsfile.gradle(version, overrides)
