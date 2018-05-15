package deploy

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'POST'
    url $(
        stub(~/\/v1\/vault\/[a-z]+\//),
        test('/v1/vault/vaultcollection/')
    )
    headers {
      contentType(applicationJson())
    }
    body(
        operationName: 'reencrypt',
        parameters: [
            'encryptionKey': 'key'
        ]
    )
    bodyMatchers {
      jsonPath('$.operationName', byEquality())
      jsonPath('$.parameters[?@encryptionKey]', byRegex(nonEmpty()))
    }
  }
  response {
    status 200
  }
}