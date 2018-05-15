package auroraconfig

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'PUT'
    url $(
        stub(~/\/v1\/auroraconfig\/[a-z]+\/validate/),
        test('/v1/auroraconfig/auroraconfigname/validate')
    )
    headers {
      contentType(applicationJson())
    }
    body(
        name: 'name',
        files: []
    )
    bodyMatchers {
      jsonPath('$.name', byRegex(nonEmpty()))
      jsonPath('$.files[?@name]', byRegex(nonEmpty()))
      jsonPath('$.files[?@contents]', byRegex(nonEmpty()))
    }
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/validate.json'))
  }
}