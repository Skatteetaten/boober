package auroraconfig

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url $(
        stub(~/\/v1\/auroraconfig\/[a-z]+\/[a-z]+/),
        test('/v1/auroraconfig/auroraconfigname/filename')
    )
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/auroraconfigfile.json'))
  }
}