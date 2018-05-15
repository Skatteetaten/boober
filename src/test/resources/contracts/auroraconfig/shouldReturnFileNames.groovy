package auroraconfig

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url $(
        stub(~/\/v1\/auroraconfig\/[a-z]+\/filenames/),
        test('/v1/auroraconfig/auroraconfigname/filenames')
    )
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/filenames.json'))
  }
}