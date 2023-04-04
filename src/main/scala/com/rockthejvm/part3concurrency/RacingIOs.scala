package com.rockthejvm.part3concurrency

import cats.effect.{IO, IOApp}

import scala.concurrent.duration.FiniteDuration
import com.rockthejvm.utils.*
import scala.concurrent.duration.*
import cats.effect.kernel.Outcome
import cats.effect.kernel.Fiber
import cats.effect.FiberIO
import cats.effect.OutcomeIO

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

  def testRacePair() =
    val meaningOfLife = runWithSleep(42, 1.second)
    val favLang       = runWithSleep("Scala", 2.seconds)
    val raceResult: IO[Either[
      (OutcomeIO[Int], FiberIO[String]),
      (FiberIO[Int], OutcomeIO[String])
    ]] = IO.racePair(meaningOfLife, favLang)

    raceResult.flatMap {
      case Left((outMol, fibLang)) =>
        fibLang.cancel >> IO("MOL won").debug >> IO(outMol).debug
      case Right((fibMol, outLang)) =>
        fibMol.cancel >> IO("Language won").debug >> IO(outLang).debug
    }

  /** Exercises:
    *
    * 1 - implement a timeout pattern with race 2 - a method to return a LOSING
    * effect from a race 3 - implement race in terms of racePair
    */

  // 1
  def timeout[A](io: IO[A], duration: FiniteDuration): IO[A] =
    IO.race(io, IO.sleep(duration))
      .flatMap {
        case Left(value) => IO.pure(value)
        case _ => IO.raiseError(new RuntimeException("Effect timed out"))
      }

  // 2
  def unrace[A, B](ioa: IO[A], iob: IO[B]): IO[Either[A, B]] =
    IO.racePair(ioa, iob)
      .flatMap {
        case Left((_, fiberB)) =>
          fiberB.join
            .flatMap {
              case Outcome.Succeeded(fb) => fb.map(Right(_))
              case Outcome.Errored(e)    => IO.raiseError(e)
              case Outcome.Canceled() =>
                IO.raiseError(new RuntimeException("user canceled an effect"))
            }

        case Right((fiberA, _)) =>
          fiberA.join
            .flatMap {
              case Outcome.Succeeded(fb) => fb.map(Left(_))
              case Outcome.Errored(e)    => IO.raiseError(e)
              case Outcome.Canceled() =>
                IO.raiseError(new RuntimeException("user canceled an effect"))
            }
      }

  // 3
  def simpleRace[A, B](ioa: IO[A], iob: IO[B]): IO[Either[A, B]] =
    IO.racePair(ioa, iob)
      .flatMap {
        case Left((Outcome.Succeeded(fa), fibB)) =>
          fibB.cancel >> fa.map(Left(_))
        case Right((fibA, Outcome.Succeeded(fb))) =>
          fibA.cancel >> fb.map(Right(_))
        case _ =>
          IO.raiseError(new RuntimeException("An error occurred"))
      }

  override def run: IO[Unit] = testRacePair().void

end RacingIOs
