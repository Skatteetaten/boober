package vault

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'PUT'
    url $(
        stub(~/\/v1\/vault\/[a-z]+/),
        test('/v1/vault/vaultcollection')
    )
    headers {
      contentType(applicationJson())
    }
    body(
        '''{"name":"responses","permissions":[],"secrets":{}}'''
    )
    bodyMatchers {
      jsonPath('$.name', byRegex(nonEmpty()))
    }
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/vaults.json'))
  }
}