package com.rockthejvm.part3concurrency

import cats.effect.IOApp
import cats.effect.IO
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import com.rockthejvm.utils.*
import scala.util.Try
import scala.concurrent.Future
import scala.concurrent.duration.*

object AsyncIOs extends IOApp.Simple:

  val threadPool           = Executors.newFixedThreadPool(8)
  val ec: ExecutionContext = ExecutionContext.fromExecutorService(threadPool)
  type Callback[A] = Either[Throwable, A] => Unit

  def computeMeaningOfLifeEither(): Either[Throwable, Int] =
    Try {
      Thread.sleep(1000)
      println(
        s"[${Thread.currentThread().getName()}] computing the meaning of life on some other thread..."
      )
      42
    }.toEither

  def computeMeaningOfLife(): Int = {
    Thread.sleep(1000)
    println(
      s"[${Thread.currentThread().getName()}] computing the meaning of life on some other thread..."
    )
    42

  }

  def computeMolOnThreadPool() =
    threadPool.execute(() => computeMeaningOfLife())

  // lift computation to an IO
  // async is a FFI
  val asyncMolIO: IO[Int] =
    IO.async_ {
      cb => // CE thread blocks (semantically) until this cb is invoked (by some other thread)
        threadPool.execute { () => // computation not managed by CE
          val result = computeMeaningOfLifeEither()
          cb(result) // CE thread is notified with the result
        }
    }

  /** Exercise
    */
  def asyncToIO[A](computation: () => A)(ec: ExecutionContext): IO[A] =
    IO.async_ { (cb: Callback[A]) =>
      ec.execute { () =>
        val result = Try(computation()).toEither
        cb(result)
      }
    }

  /** Exercise: lift a future into IO
    */
  lazy val molFuture: Future[Int] = Future { computeMeaningOfLife() }(ec)

  def futureToIO[A](computation: => Future[A])(implicit
      ec: ExecutionContext
  ): IO[A] =
    IO.async_ { case cb =>
      computation
        .onComplete { tryResult =>
          val result = tryResult.toEither
          cb(result)
        }
    }

  val futureLifted: IO[Int] =
    futureToIO(molFuture)(ec)

  /** Exercise: never ending IO
    */
  def never: IO[Int] = IO.async_[Int](_ => ())

  /*
    FULL ASYNC Call
   */
  def demoAsyncCancellation() =
    val meaningOfLifeIOV2: IO[Int] =
      IO.async { (cb: Callback[Int]) =>
        /*
        finalizer in case computation getc cancelled
        finalizers are of type IO[Unit]
        not specifying finalizer => Option[IO[Unit]]
        creating option is an effect => IO[Option[IO[Unit]]]
         */

        IO {
          threadPool.execute { () =>
            val result = computeMeaningOfLifeEither()
            cb(result)
          }
        }.`as`(Some(IO("Cancelled!").debug.void))
        // return IO[Option[IO[Unit]]]
      }

    for
      fib <- meaningOfLifeIOV2.start
      _   <- IO.sleep(500.millis) >> IO("cancelling...").debug >> fib.cancel
      _   <- fib.join
    yield ()

  end demoAsyncCancellation

  override def run: IO[Unit] =
    demoAsyncCancellation().debug >> IO(threadPool.shutdown())
