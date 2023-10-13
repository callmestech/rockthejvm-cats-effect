package com.rockthejvm.part5polymorphic

import cats.effect.syntax.spawn.*
import cats.effect.syntax.monadCancel.*
import cats.syntax.parallel.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.effect.{IO, IOApp, Ref, Concurrent}
import cats.effect.kernel.{Deferred, Spawn}

import com.rockthejvm.utils.general.*
import scala.concurrent.duration.*
import cats.effect.kernel.Fiber
import cats.effect.kernel.Outcome

object PolymorphicCoordination extends IOApp.Simple:
  trait MyConcurrent[F[_]] extends Spawn[F]:
    def ref[A](a: A): F[Ref[F, A]]
    def deferred[A]: F[Deferred[F, A]]
  end MyConcurrent

  val concurrentIO = Concurrent[IO] // given instance of Concurrent[IO]
  val deferred     = Deferred[IO, Int]
  val deferredV2   = concurrentIO.deferred[Int]
  val reference    = concurrentIO.ref(42)

  // capabilities: pure, map/flatmap, raiseError, uncancelable, start, ref + deferred

  def alarmNotifier[F[_]](using concurrent: Concurrent[F]) =
    def notifyWhenComplete(signal: Deferred[F, Int]): F[Unit] =
      for
        _ <- concurrent.pure("[notifier] waiting...").debug
        i <- signal.get
        _ <- concurrent.pure(s"[notifier] time's up! received $i").debug
      yield ()

    def clock(ref: Ref[F, Int], signal: Deferred[F, Int]): F[Unit] =
      for
        _    <- unsafeSleep(1.second)
        time <- ref.updateAndGet(_ + 1)
        _    <- concurrent.pure(s"[clock] Current time is $time").debug
        _ <-
          if time == 10 then signal.complete(time).void
          else clock(ref, signal)
      yield ()

    for
      ref         <- concurrent.ref(0)
      signal      <- concurrent.deferred[Int]
      notifierFib <- concurrent.start(notifyWhenComplete(signal))
      clockFib    <- concurrent.start(clock(ref, signal))
      _           <- notifierFib.join
      _           <- clockFib.join
    yield ()

  // Exercises:
  // 1. Generalize racePair
  // 2. Generalize the Mutex concurrency primitive for any F
  type RaceResult[F[_], A, B] = Either[
    (Outcome[F, Throwable, A], Fiber[F, Throwable, B]),
    (Fiber[F, Throwable, A], Outcome[F, Throwable, B]),
  ]
  type EitherOutcome[F[_], A, B] =
    Either[Outcome[F, Throwable, A], Outcome[F, Throwable, B]]

  def ourRacePair[F[_], A, B](fa: F[A], fb: F[B])(using
      C: Concurrent[F]
  ): F[RaceResult[F, A, B]] =
    C.uncancelable { poll =>
      for
        signal <- C.deferred[EitherOutcome[F, A, B]]
        fibA <- fa.guaranteeCase(outA => signal.complete(Left(outA)).void).start
        fibB <- fb
          .guaranteeCase(outB => signal.complete(Right(outB)).void)
          .start
        res <- signal.get.onCancel {
          for
            cancelFibA <- fibA.cancel.start
            cancelFibB <- fibB.cancel.start
            _          <- cancelFibA.join
            _          <- cancelFibB.join
          yield ()

        }
      yield res match
        case Left(outA)  => Left(outA -> fibB)
        case Right(outB) => Right(fibA -> outB)
    }

  override def run: IO[Unit] =
    alarmNotifier[IO]
