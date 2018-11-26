package test.luxoft.ratelimiter.throttling

/**
  * A set of permissions.
  */
trait Permissions {

  /**
    * Borrows a permission. If permission is available -- returns {@code true}.
    *
    * @return {@code true} if permission is available
    */
  def take(): Boolean
}
