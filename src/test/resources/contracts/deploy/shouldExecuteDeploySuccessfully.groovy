package deploy

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'PUT'
    url $(
        stub(~/\/v1\/apply\/[a-z]+/),
        test('/v1/apply/paas')
    )
    headers {
      contentType(applicationJson())
    }
    body(
        '''{"applicationDeploymentRefs":[{"environment":"env","application":"app"}],"applicationIds":[],"overrides":{},"deploy":true,"allRefs":[{"environment":"env","application":"app"}]}'''
    )
    bodyMatchers {
      jsonPath('$.applicationDeploymentRefs[?@environment]', byRegex(nonEmpty()))
      jsonPath('$.applicationDeploymentRefs[?@application]', byRegex(nonEmpty()))
      jsonPath('$.deploy', byRegex(anyBoolean()))
    }
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/deploy.json'))
  }
}