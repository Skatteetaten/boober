package auroraconfig

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'PUT'
    url $(
        consumer(~/\/v1\/auroraconfig\/[a-z]+\/validate/),
        producer('/v1/auroraconfig/auroraconfigname/validate')
    )
    headers {
      contentType(applicationJson())
    }
    body(
        name: $(
            consumer(~/.+/),
            producer('name')
        ),
        files: []
    )
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/validate.json'))
  }
}