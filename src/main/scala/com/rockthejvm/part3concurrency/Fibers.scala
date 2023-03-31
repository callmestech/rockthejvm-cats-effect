package com.rockthejvm.part3concurrency

import cats.effect.IOApp
import cats.effect.IO
import com.rockthejvm.utils.*
import cats.effect.kernel.Fiber
import cats.effect.FiberIO
import cats.effect.kernel.Outcome
import cats.effect.kernel.Outcome.*
import scala.concurrent.duration.*

object Fibers extends IOApp.Simple {

  val meaningOfLife: IO[Int] = IO.pure(42)
  val favLang                = IO.pure("Scala")

  def sameThreadIOs() = for {
    _ <- meaningOfLife.debug
    _ <- favLang.debug
  } yield ()

  def createFiber: Fiber[IO, Throwable, String] = ???

  // an allocation of a fiber is effectful operation
  // the fiber is not actually started, but the allocation is wrapped in another effect
  val aFiber: IO[FiberIO[Int]] = meaningOfLife.debug.start

  def differentThreadIOs() = for {
    _ <- aFiber
    _ <- favLang.debug
  } yield ()

  // joining a fiber
  def runOnSomeOtherThread[A](io: IO[A]): IO[Outcome[IO, Throwable, A]] =
    for {
      fib    <- io.start
      result <- fib.join // an effect which waits for the fiber to terminate
    } yield result

    /*
     *  possible outcomes
     * success with an IO
     * failure with an exception
     * cancelled
     */

    /*
      IO[ResultType of fib.join]
      fib.join = Outcome[IO, Throwable, A]
     */

  val someIOOnAnotherThread = runOnSomeOtherThread(meaningOfLife)
  val someResultFromAnotherThread = someIOOnAnotherThread.flatMap {
    case Succeeded(effect) => effect
    case Errored(e)        => IO(0)
    case Canceled()        => IO(0)
  }

  def throwOnAnotherThread() =
    for {
      fib <- IO.raiseError[Int](new RuntimeException("no number for you")).start
      result <- fib.join
    } yield result

  def testCancel() = {
    val task = IO("starting").debug >> IO.sleep(1.second) >> IO("done").debug
    val taskWithCancellationHandler =
      task.onCancel(IO("I'm being cancelled").debug.void)

    for {
      fib <- taskWithCancellationHandler.start // on a separate thread
      _ <- IO.sleep(500.millis) >> IO(
        "cancelling"
      ).debug // running on the calling thread
      _   <- fib.cancel
      res <- fib.join
    } yield res
  }

  /** Exercises
    *
    *   1. Write a function that runs an IO on another thread, and, depending on
    *      the result of the fiber
    *   - return the result in an IO
    *   - if errored or cancelled, return a failed IO
    */

  def processResultsFromFiber[A](io: IO[A]): IO[A] =
    for {
      fiber <- io.debug.start
      result <- fiber.join.flatMap {
        case Succeeded(fa) => fa
        case Errored(e)    => IO.raiseError(e)
        case Canceled() =>
          IO.raiseError(new RuntimeException("Computation cancelled"))
      }

    } yield result

  /** 2. Write a function that takes two IOs, runs them on a different fibers
    * and returns an IO with a tuple containing both result:
    *   - if both IOs complete successfully, tuple their results
    *   - if the first IO returns an error, raise that error (ignoring the
    *     second IO's result/error)
    *   - if the first IO doesn't error but second IO returns an error, raise
    *     that error
    *   - if one (or both) cancelled, raise a RuntimeException
    */
  def tupleIOs[A, B](ioa: IO[A], iob: IO[B]): IO[(A, B)] =
    for {
      fiberA <- ioa.start
      fiberB <- iob.start
      resA   <- fiberA.join
      resB   <- fiberB.join
      res <- (resA, resB) match {
        case (Succeeded(fa), Succeeded(fb)) =>
          fa.flatMap(a => fb.map(a -> _))
        case (Errored(e), _) =>
          IO.raiseError(e)
        case (_, Errored(e)) =>
          IO.raiseError(e)
        case _ =>
          IO.raiseError(new RuntimeException("Both fibers were cancelled"))
      }
    } yield res

  /** 3. Write a function that adds a timeout to an IO:
    *   - IO runs a fiber
    *   - if the timeout duration passes, then the fiber is cancelled
    *   - the method returns an IO[A] which contains:
    *     - the original value if the computation is successful before the
    *       timeout signal
    *     - the exception if the computation is failed before the timeout signal
    *     - a RuntimeException if it times out (i.e. cancelled by the timeout)
    */
  def timeout[A](io: IO[A], duration: FiniteDuration): IO[A] = {
    val computation = for {
      fib <- io.start
      _ <- (IO.sleep(duration) >> fib.cancel).start // careful - fibers can leak
      result <- fib.join
    } yield result

    computation.flatMap {
      case Succeeded(fa) => fa
      case Errored(e)    => IO.raiseError(e)
      case Canceled() =>
        IO.raiseError(new RuntimeException("Computation cancelled"))
    }
  }

  def testEx1 = {
    val computation =
      IO("starting").debug >> IO.sleep(1.second) >> IO("done!").debug >> IO(42)
    processResultsFromFiber(computation).void
  }

  def testEx2 = {
    val firstIO  = IO.sleep(2.seconds) >> IO(1).debug
    val secondIO = IO.sleep(3.seconds) >> IO(2).debug
    tupleIOs(firstIO, secondIO).debug.void
  }

  def testEx3 = {
    val computation =
      IO("starting").debug >> IO.sleep(1.second) >> IO("done!").debug >> IO(42)
    timeout(computation, 500.millis).debug.void
  }

  def run: IO[Unit] =
    testEx3
}
