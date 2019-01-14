def jenkinsfile

def overrides = [
    scriptVersion  : 'v6',
    pipelineScript: 'https://git.aurora.skead.no/scm/ao/aurora-pipeline-scripts.git',
    openShiftBaseImage: 'yeaster',
    openShiftBaseImageVersion: '1',
    disableAllReports: true,
    credentialsId: "github",
    suggestVersionAndTagReleases: [
      [branch: 'master', versionHint: '2']
    ]
]

fileLoader.withGit(overrides.pipelineScript,, overrides.scriptVersion) {
   jenkinsfile = fileLoader.load('templates/leveransepakke')
}

jenkinsfile.gradle(overrides.scriptVersion, overrides)
