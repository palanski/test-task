package test.luxoft.ratelimiter

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{RejectionHandler, Route}
import test.luxoft.ratelimiter.throttling.TooManyRequestsRejection

/**
  * Server router, collects routes for all the app services
  */
object ServerRouter {

  def route(routes: Route*): Route = {

    val rejectionHandler = RejectionHandler.newBuilder()
        .handle {
          case TooManyRequestsRejection =>
            complete(StatusCodes.TooManyRequests)
        }.result()

    handleRejections(rejectionHandler) {
      routes.reduce(_ ~ _)
    }
  }
}
