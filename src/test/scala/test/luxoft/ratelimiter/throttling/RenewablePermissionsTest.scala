package test.luxoft.ratelimiter.throttling

import java.util.concurrent.{CountDownLatch, Executors}

import org.scalacheck.Gen
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.tagobjects.Slow

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Random

class RenewablePermissionsTest
    extends FlatSpec
        with GeneratorDrivenPropertyChecks
        with Matchers {

  val validApsRange: Range = 1 to 1000 // allocations per second

  def validPpsRange(aps: Int): Range = 1 to aps * Short.MaxValue // permissions per second

  "Constructor" should "not fail if arguments are valid" in {

    val validArgs = for {
      aps <- Gen.oneOf(validApsRange)
      pps <- Gen.oneOf(validPpsRange(aps))
    } yield (pps, aps)

    forAll(validArgs) { case (pps, aps) => new RenewablePermissions(pps, aps) }
  }

  it should s"fail with ${classOf[IllegalArgumentException].getName} if either argument is invalid" in {

    val invalidAps = for {
      aps <- Gen.choose(Int.MinValue, Int.MaxValue).filterNot(validApsRange.contains)
      pps <- Gen.choose(Int.MinValue, Int.MaxValue)
    } yield (pps, aps)

    val invalidPps = for {
      aps <- Gen.oneOf(validApsRange)
      pps <- Gen.choose(Int.MinValue, Int.MaxValue).filterNot(validPpsRange(aps).contains)
    } yield (pps, aps)

    forAll(Gen.oneOf(invalidAps, invalidPps)) {
      case (pps: Int, aps: Int) => assertThrows[IllegalArgumentException](new RenewablePermissions(pps, aps))
    }
  }

  "take" should "provide the configured number of permissions" taggedAs Slow in {

    val permissionCount = 3
    val threadCount = 20

    val exec = Executors.newFixedThreadPool(threadCount)
    implicit val execCtx: ExecutionContext = ExecutionContext.fromExecutor(exec)

    val permissions = new RenewablePermissions(permissionCount, 2)
    val startSignal = new CountDownLatch(1)

    val permissionConsumer = () => Future[Int] {
      val delay = new Random()
      startSignal.await()
      // as permissions are allocated twice per second (aps = 2), make sure that total execution time
      // does not exceed 1000 ms (19 * 50 (max delay)) and is not shorter than 500 ms
      1 to 19 map (i => {
        Thread.sleep(30 + delay.nextInt(20))
        permissions.take()
      }) count (p => p)
    }

    val permissionConsumers = 1 to threadCount map (_ => permissionConsumer())

    // wait until a new second starts as permission allocation is synced with the clock
    while (System.currentTimeMillis() % 1000 > 40) {}

    // start to consume permissions
    startSignal.countDown()

    val actualPermissionCount = permissionConsumers.map(c => Await.result(c, Duration.Inf)).sum

    exec.shutdown()

    assertResult(permissionCount)(actualPermissionCount)
  }
}