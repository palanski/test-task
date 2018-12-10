package test.luxoft.ratelimiter.foo

import scala.concurrent.duration._

object FooService {

  def process(maybeToken: Option[String]): String = {
    val deadline = 5.millis.fromNow
    while (deadline.hasTimeLeft()) {} // simulate workload
    maybeToken.map(t => s"Foo finished for the token $t").getOrElse("Foo finished for an unauthorized user")
  }
}
