package deploy

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'PUT'
    url $(
        stub(~/\/v1\/apply\/[a-z]+/),
        test('/v1/apply/invalid')
    )
    headers {
      contentType(applicationJson())
    }
    body(
        applicationIds: [],
        overrides: {},
        deploy: true
    )
    bodyMatchers {
      jsonPath('$.applicationDeploymentRefs[?@environment]', byRegex(nonEmpty()))
      jsonPath('$.applicationDeploymentRefs[?@application]', byRegex(nonEmpty()))
      jsonPath('$.deploy', byRegex(anyBoolean()))
    }
  }
  response {
    status 200
    body(file('responses/deploy-failed.json'))
    headers {
      contentType(applicationJson())
    }
  }
}