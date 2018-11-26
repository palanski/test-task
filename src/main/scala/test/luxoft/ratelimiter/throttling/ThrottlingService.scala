package test.luxoft.ratelimiter.throttling

import test.luxoft.ratelimiter.sla.SlaService

trait ThrottlingService {

  val graceRps: Int // configurable

  val slaService: SlaService // use mocks/stubs for testing

  // Should return true if the request is within allowed RPS.
  def isRequestAllowed(token: Option[String]): Boolean
}
