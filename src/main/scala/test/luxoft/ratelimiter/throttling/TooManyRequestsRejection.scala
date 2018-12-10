package test.luxoft.ratelimiter.throttling

import akka.http.scaladsl.server.Rejection

case object TooManyRequestsRejection extends Rejection
