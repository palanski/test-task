package test.luxoft.ratelimiter

import java.util.concurrent.Executors

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import test.luxoft.ratelimiter.foo.{FooRouter, FooService}
import test.luxoft.ratelimiter.sla.DummySlaService
import test.luxoft.ratelimiter.throttling.SimpleThrottlingService

import scala.concurrent.ExecutionContext
import scala.io.StdIn

/**
  * Just to see that rest service works.
  */
object App {

  def main(args: Array[String]): Unit = {
    val slaService = new DummySlaService(10)(execContext(5))
    val throttlingService = new SimpleThrottlingService(10, slaService)(execContext(5))

    val fooRoute = FooRouter.route(throttlingService, FooService.process)

    val serverRoute = ServerRouter.route(fooRoute)

    implicit val system = ActorSystem("my-system")
    implicit val materializer = ActorMaterializer()

    implicit val executionContext = system.dispatcher

    val bindingFuture = Http().bindAndHandle(serverRoute, "localhost", 8080)

    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return

    bindingFuture
        .flatMap(_.unbind()) // trigger unbinding from the port
        .onComplete(_ => system.terminate()) // and shutdown when done

  }

  private def execContext(threadCount: Int): ExecutionContext = {
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(threadCount))
  }
}
