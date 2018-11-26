package test.luxoft.ratelimiter.throttling

import java.util.concurrent.ConcurrentHashMap

import com.typesafe.scalalogging.LazyLogging
import test.luxoft.ratelimiter.sla.SlaService

import collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

class SimpleThrottlingService(
    override val graceRps: Int,
    override val slaService: SlaService,
    private val permissions: Int => Permissions = pps => new RenewablePermissions(pps, 10))
    (implicit val execContext: ExecutionContext) extends ThrottlingService with LazyLogging {

  private val unauthPermissions = permissions(graceRps)

  private val tokenPermissions = new ConcurrentHashMap[String, Permissions]().asScala
  private val userPermissions = new ConcurrentHashMap[String, Permissions]().asScala

  override def isRequestAllowed(token: Option[String]): Boolean = {
    token map handleAuthorized getOrElse handleUnauthorized
  }

  private def handleAuthorized(token: String) = {
    tokenPermissions get token map (_.take()) getOrElse {
      tokenPermissions putIfAbsent(token, unauthPermissions) map (_ take()) getOrElse {
        requestSla(token)
        tokenPermissions(token) take()
      }
    }
  }

  private def handleUnauthorized() = unauthPermissions.take()

  private def requestSla(token: String): Unit = {
    slaService getSlaByToken token onComplete {
      case Success(sla) =>
        val newUserCounter = permissions(sla.rps)
        val userCounter = userPermissions putIfAbsent(sla.user, newUserCounter) getOrElse newUserCounter
        tokenPermissions put(token, userCounter)

      case Failure(e) => logger.error(s"Getting SLA failed for token {$token}: ", e)
    }
  }
}