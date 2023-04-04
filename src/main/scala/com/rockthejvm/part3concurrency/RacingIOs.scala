package com.rockthejvm.part3concurrency

import cats.effect.{IO, IOApp}

import scala.concurrent.duration.FiniteDuration
import com.rockthejvm.utils.*
import scala.concurrent.duration.*
object RacingIOs extends IOApp.Simple:
  def runWithSleep[A](value: A, duration: FiniteDuration): IO[A] =
    (
      IO(s"starting computation").debug >>
        IO.sleep(duration) >>
        IO(s"computation for $value: done") >>
        IO(value)
    ).onCancel(IO(s"computation CANCELED for $value").debug.void)

  def testRace() =
    val meaningOfLife                  = runWithSleep(42, 1.second)
    val favLang                        = runWithSleep("Scala", 2.seconds)
    val first: IO[Either[Int, String]] = IO.race(meaningOfLife, favLang)
    /*
     * - both IOs run on separate fibers
     * - the first one to finish will complete the result
     * - the loser will be cancelled
     * */
    first.flatMap {
      case Left(value)  => IO(s"meaning of life $value")
      case Right(value) => IO(s"favourite language is $value")
    }

  override def run: IO[Unit] = testRace().void

end RacingIOs
