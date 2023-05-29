package com.rockthejvm.part3concurrency

import cats.effect.IOApp
import cats.effect.IO
import scala.concurrent.duration.*
import com.rockthejvm.utils.debug
import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors

object BlockingIOs extends IOApp.Simple:

  val someSleeps =
    for
      _ <- IO
        .sleep(1.second)
        .debug // SEMANTIC BLOCKING, no actual thread is blocked
      _ <- IO.sleep(1.second).debug
    yield ()

  // really blocking IOs
  val blockingIO =
    IO.blocking {
      Thread.sleep(1000)
      println(s"[${Thread.currentThread().getName()}] computed a blocking code")
      42
    }

  // yielding
  val iosOnManyThreads =
    for
      _ <- IO("first").debug
      _ <-
        IO.cede // a signal to yield control over the thread - equivalent to IO.shift
      _ <- IO(
        "second"
      ).debug // the res of this effect may run on another thread (not necessarily)
      _ <- IO.cede
      _ <- IO("third").debug
    yield ()

  def testThousandEffectsSwitch() =
    val ec: ExecutionContext =
      ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(8))
    (0 to 1000).map(IO.pure).reduce(_.debug >> IO.cede >> _.debug).evalOn(ec)

  /*
   - blocking calls & IO.sleep and yield control over the calling thread automatically
   */

  override def run: IO[Unit] =
    testThousandEffectsSwitch().void
