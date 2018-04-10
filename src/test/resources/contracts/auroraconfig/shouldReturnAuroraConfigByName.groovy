package auroraconfig

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'GET'
    url $(
        consumer(~/\/v1\/auroraconfig\/[a-z]+/),
        producer('/v1/auroraconfig/auroraconfigname')
    )
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/auroraconfig.json'))
  }
}