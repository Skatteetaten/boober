package userannotation

import org.springframework.cloud.contract.spec.Contract

Contract.make {
  request {
    method 'PATCH'
    url $(
        stub(~/\/v1\/users\/annotations\/[A-Za-z]+/),
        test('/v1/users/annotations/myCustomAnnotation')
    )
    headers {
      contentType(applicationJson())
    }
    body('''{ "key": "value" }''')
  }
  response {
    status 200
    headers {
      contentType(applicationJson())
    }
    body(file('responses/userannotation.json'))
  }
}