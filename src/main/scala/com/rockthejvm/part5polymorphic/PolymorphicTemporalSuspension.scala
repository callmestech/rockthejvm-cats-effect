package com.rockthejvm.part5polymorphic

import cats.effect.syntax.spawn.*
import cats.effect.syntax.concurrent.*
import cats.effect.syntax.monadCancel.*
import cats.syntax.apply.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.effect.{IO, IOApp, Temporal}
import cats.effect.kernel.Concurrent
import scala.concurrent.duration.FiniteDuration
import com.rockthejvm.part5polymorphic.PolymorphicCancellation.sleepUnsafe
import com.rockthejvm.utils.general.debug

import scala.concurrent.duration.*
import java.time.temporal.TemporalAmount

object PolymorphicTemporalSuspension extends IOApp.Simple:

  // Temporal - time-blocking effects
  trait MyTemporal[F[_]] extends Concurrent[F]:
    // semantically blocks this fiber for a specified time
    def sleep(time: FiniteDuration): F[Unit]
  end MyTemporal

  // abilities: pure, map/flatMap, raiseError, uncancelable, start, ref/deferred, +sleep
  val temporalIO = Temporal[IO]
  val chainOfEffects =
    IO("Loading...").debug *>
      IO.sleep(1.second) *>
      IO("Game ready!").debug

  val chainOfEffects2 =
    temporalIO.pure("Loading...").debug *>
      temporalIO.sleep(1.second) *>
      temporalIO.pure("Game ready!").debug

  /** Exercises: generalize the following piece
    */
  def timeout[F[_], A](io: F[A], duration: FiniteDuration)(using
      T: Temporal[F]
  ): F[A] =
    T.sleep(duration).race(io).flatMap {
      case Left(_) =>
        T.raiseError(new RuntimeException("Computation timed out."))
      case Right(value) =>
        T.pure(value)
    }

  override def run: IO[Unit] = ???
