package auroraconfig

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'PUT'
    url $(
        consumer(~/\/v1\/auroraconfig\/[a-z]+\/[a-z]+/),
        producer('/v1/auroraconfig/auroraconfigname/filename')
    )
    headers {
      contentType(applicationJson())
    }
    body(
        content: 'test-content'
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