package test.luxoft.ratelimiter.throttling

import java.util.concurrent.{CountDownLatch, Executor, Executors}
import java.util.concurrent.atomic.AtomicInteger

import scala.language.implicitConversions
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FlatSpec, Matchers, OneInstancePerTest}
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ConductorMethods
import test.luxoft.ratelimiter.sla.{Sla, SlaService}

import scala.concurrent.{ExecutionContext, Future, Promise}

class SimpleThrottlingServiceTest
    extends FlatSpec
        with GeneratorDrivenPropertyChecks
        with Matchers
        with MockFactory
        with ConductorMethods
        with OneInstancePerTest {

  private val graceRps = 1

  private val user = "foo"
  private val userToken = "fooToken"
  private val userRps = 2

  private val unauthPerms = mock[Permissions]("unauthPerms")
  private val authPerms = mock[Permissions]("authPerms")
  private val slaService = mock[SlaService]("slaService")
  private val permFactory = mockFunction[Int, Permissions]("permFactory")

  "unauthorized user" should "be throttled by (the same) unauthorized user counter" in {

    permFactory expects graceRps returning unauthPerms once()
    (unauthPerms.take: () => Boolean) expects() returning true once()
    (unauthPerms.take: () => Boolean) expects() returning false once()

    implicit val execCtx: ExecutionContext = mock[ExecutionContext]
    val ts = new SimpleThrottlingService(graceRps, slaService, permFactory)

    assertResult(true)(ts.isRequestAllowed(None))
    assertResult(false)(ts.isRequestAllowed(None))
  }

  "authorized user" should "be throttled by (the same) unauthorized user counter until their SLA is obtained" in {

    permFactory expects graceRps returning unauthPerms once()
    (unauthPerms.take: () => Boolean) expects() returning true once()
    (unauthPerms.take: () => Boolean) expects() returning false once()

    slaService.getSlaByToken _ expects userToken returning Future.never once()

    implicit val execCtx: ExecutionContext = mock[ExecutionContext]
    val ts = new SimpleThrottlingService(graceRps, slaService, permFactory)

    assertResult(true)(ts.isRequestAllowed(None))
    assertResult(false)(ts.isRequestAllowed(Some(userToken))) // permissions are exhausted by unauth user
  }

  it should "be throttled by (the same) authorized user counter after their SLA is obtained" in {

    permFactory expects graceRps returning unauthPerms once()
    permFactory expects userRps returning authPerms once()
    (authPerms.take: () => Boolean) expects() returning true once()
    (authPerms.take: () => Boolean) expects() returning false once()

    slaService.getSlaByToken _ expects userToken returning Future.successful(Sla(user, userRps)) once()

    val sameThreadExecutor: Executor = r => r.run()
    implicit val execContext: ExecutionContext = ExecutionContext.fromExecutor(sameThreadExecutor)

    val ts = new SimpleThrottlingService(graceRps, slaService, permFactory)

    assertResult(true)(ts.isRequestAllowed(Some(userToken)))
    assertResult(false)(ts.isRequestAllowed(Some(userToken)))
  }

  "SLA service running for a token" should "not be called again" in {

    permFactory expects graceRps returning unauthPerms once()
    (unauthPerms.take: () => Boolean) expects() returning true once()
    (unauthPerms.take: () => Boolean) expects() returning false once()

    slaService.getSlaByToken _ expects userToken returning Future.never once()

    implicit val execCtx: ExecutionContext = mock[ExecutionContext]
    val ts = new SimpleThrottlingService(graceRps, slaService, permFactory)

    assertResult(true)(ts.isRequestAllowed(Some(userToken)))
    assertResult(false)(ts.isRequestAllowed(Some(userToken)))
  }

  "throttling service" should "be thread safe" in {

    val clientCount = 3
    val sla = Promise[Sla]

    permFactory expects graceRps returning unauthPerms once()
    permFactory expects userRps returning authPerms once()

    unauthPerms.take _ expects() returning true repeated graceRps times()
    unauthPerms.take _ expects() returning false anyNumberOfTimes()

    authPerms.take _ expects() returning true repeated userRps times()
    authPerms.take _ expects() returning false anyNumberOfTimes()

    val exec = Executors.newFixedThreadPool(1)
    implicit val execCtx: ExecutionContext = ExecutionContext.fromExecutor(exec)

    slaService.getSlaByToken _ expects userToken returning sla.future

    val ts = new SimpleThrottlingService(graceRps, slaService, permFactory)

    val actualUnauthPermissions = new AtomicInteger()
    val actualAuthPermissions = new AtomicInteger()

    implicit def boolToInt(b: Boolean): Int = if (b) 1 else 0

    def thread(i: Int) = threadNamed(s"Client $i") {
      // Try to consume all the available unauthorized user permissions
      for (_ <- 1 to graceRps) {
        actualUnauthPermissions.addAndGet(ts.isRequestAllowed(Some(userToken)))
      }

      waitForBeat(2)

      // Consume all the available authorized user permissions
      while (actualAuthPermissions.addAndGet(ts.isRequestAllowed(Some(userToken))) < userRps) {}
    }

    for (i <- 1 to clientCount) thread(i) // create client threads

    threadNamed("SLA Provider") {
      waitForBeat(1)
      sla.success(Sla(user, userRps))
    }

    whenFinished {
      exec.shutdown()
      assert(graceRps >= actualUnauthPermissions.get())
      assertResult(userRps)(actualAuthPermissions.get())
    }
  }
}
