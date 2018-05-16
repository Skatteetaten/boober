package auroraconfig

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'PUT'
    url $(
        stub(~/\/v1\/auroraconfig\/[a-z]+\/[a-z]+/),
        test('/v1/auroraconfig/auroraconfigname/filename')
    )
    headers {
      contentType(applicationJson())
    }
    body(
        content: 'test-content'
    )
    bodyMatchers {
      jsonPath('$.content', byRegex(nonEmpty()))
    }
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/auroraconfigfile.json'))
  }
}