package test.luxoft.ratelimiter

import java.util.concurrent.{ExecutorService, Executors}
import java.util.concurrent.atomic.LongAdder

import akka.actor.ActorSystem
import akka.http.javadsl.model.HttpResponse
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.HttpRequest
import akka.stream.ActorMaterializer
import org.scalamock.scalatest.MockFactory
import org.scalatest.FlatSpec
import org.scalatest.tagobjects.Slow
import test.luxoft.ratelimiter.foo.{FooRouter, FooService}
import test.luxoft.ratelimiter.sla.DummySlaService
import test.luxoft.ratelimiter.throttling.SimpleThrottlingService

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class RestServiceLoadTest extends FlatSpec with MockFactory {

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = system.dispatcher

  // TODO: Try Gatling if it is possible to run it as a system test
  "rest service" should "handle approximately N * RPS * T requests" taggedAs Slow in {

    val N = 8 // number of users (threads)
    val RPS = 50 // requests per second
    val T = 30.seconds // test duration

    val slaServiceExecContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(N))

    val slaService = new DummySlaService(RPS)(slaServiceExecContext)
    val throttlingService = new SimpleThrottlingService(RPS, slaService)(slaServiceExecContext)

    val fooRoute = FooRouter.route(throttlingService, FooService.process)
    val serverRoute = ServerRouter.route(fooRoute)

    val bindingFuture = Http().bindAndHandle(serverRoute, "localhost", 8080)

    val (handledRequests, allRequests) = (1 to N)
        .map {
          token =>
            Future {
              val deadLine = T.fromNow
              var handledRequests = 0
              var allRequests = 0

              while (deadLine.hasTimeLeft()) {
                val responseFuture: Future[HttpResponse] = Http().singleRequest(
                  HttpRequest(uri = "http://localhost:8080/foo")
                      .withHeaders(RawHeader(FooRouter.tokenHeaderName, token.toString))
                )

                val response = Await.result(responseFuture, Duration.Inf)

                if (response.status().intValue() == 200) {
                  handledRequests = handledRequests + 1
                }

                allRequests = allRequests + 1
              }

              (handledRequests, allRequests)
            }
        }
        .map(f => Await.result(f, Duration.Inf))
        .reduce((a, b) => (a._1 + b._1, a._2 + b._2))

    // If machine is slow, use actual number of all the processed requests. 95% are expected to be handled.
    val expectedMinHandledRequests = Math.min(N * RPS * T.toSeconds, allRequests) * 0.95

    assertResult(true)(expectedMinHandledRequests <= handledRequests)

    bindingFuture
        .flatMap(_.unbind()) // trigger unbinding from the port
        .onComplete(_ => system.terminate()) // and shutdown when done
  }
}
