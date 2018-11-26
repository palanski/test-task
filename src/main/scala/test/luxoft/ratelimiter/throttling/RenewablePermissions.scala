package test.luxoft.ratelimiter.throttling

import java.lang.Short
import java.util.concurrent.atomic.AtomicLong

import scala.math.{floor, min, round}

/**
  * A set of permissions that renews on a time basis. Permission can be allocated (renewed) several times per second.
  * In this case the {@code aps} argument has to be > 1 and for each allocation there will be roughly
  * {@code pps/aps} permissions available. If there are more allocations per second than permissions
  * per second, permissions per second will be used as an allocation rate, providing one permission per allocation.
  *
  * Maximum allowed number of permissions per allocation is 32767.
  * Maximum allowed number of allocations per second is 1000.
  *
  * @param pps -- number of permissions per second [0, 32767*aps]
  * @param aps -- permission allocations per second [1, 1000], pps/aps permissions are available for each allocation
  */
class RenewablePermissions(
    pps: Int, // permissions per second
    aps: Int // allocations per second
) extends Permissions {

  require(1 to Short.MAX_VALUE * aps contains pps, s"Permission rate must be in [1, ${Short.MAX_VALUE * aps}]. Was $pps.")
  require(1 to 1000 contains aps, s"Allocation rate must be in [1, 1000]. Was $aps.")

  private val allocRate = min(pps, aps)

  private val mspa = 1000F / allocRate // msec per allocation

  private val allocs: Array[Long] = {
    val ppa = pps.toFloat / allocRate // permissions per allocation

    (1 to allocRate).view.map(a => {
      val allocExpiration = round(a * mspa)
      val permCount = round(a * ppa) - round((a - 1) * ppa)
      (allocExpiration.toLong << Short.SIZE) | permCount
    }).toArray
  }

  private val state = new AtomicLong()

  override def take(): Boolean = {
    state.updateAndGet(s => {
      val now = System.currentTimeMillis
      if ((now << Short.SIZE) < s) decCount(s) else allocateAndDecCount(now)
    }).toShort >= 0
  }

  private def decCount(state: Long) = {
    val count = state.toShort
    val updatedCount = (~(count >> Short.SIZE) & count) - 1 // if (count < 0) -1 else count - 1
    state & 0xFFFF0000 | (updatedCount & 0xFFFFL)
  }

  private def allocateAndDecCount(now: Long) = {
    val msSinceSecondStart = now % 1000
    val allocIndex = floor(msSinceSecondStart / mspa)
    val secondStart = now - msSinceSecondStart
    (secondStart << Short.SIZE) + allocs(allocIndex.toInt) - 1
  }
}
