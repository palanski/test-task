package test.luxoft.ratelimiter.sla

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class DummySlaService(rps: Int)(implicit val execContext: ExecutionContext) extends SlaService {

  override def getSlaByToken(token: String): Future[Sla] = {
    Future {
      val deadline = 250.millis.fromNow
      while (deadline.hasTimeLeft()) {} // simulate workload
      Sla(token, rps)
    }
  }
}