package deploy

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'PUT'
    url $(
        stub(~/\/v1\/vault\/[a-z]+\/.*/),
        test('/v1/vault/vaultcollection/vault/filename')
    )
    headers {
      contentType(applicationJson())
    }
    body(
        contents: 'dGVzdA=='
    )
    bodyMatchers {
      jsonPath('$.contents', byRegex(nonEmpty()))
    }
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/vaultfile.json'))
  }
}