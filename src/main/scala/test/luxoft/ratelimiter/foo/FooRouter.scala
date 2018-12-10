package test.luxoft.ratelimiter.foo

import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import test.luxoft.ratelimiter.throttling.{ThrottlingService, TooManyRequestsRejection}

/**
  * Router for a foo service
  */
object FooRouter {

  val tokenHeaderName = "token"

  def route(throttlingService: ThrottlingService, fooService: Option[String] => String): Route =
    path("foo") {
      get {
        optionalHeaderValueByName(tokenHeaderName) { maybeToken =>
          if (throttlingService.isRequestAllowed(maybeToken)) {
            complete(fooService(maybeToken))
          } else {
            reject(TooManyRequestsRejection)
          }
        }
      }
    }
}