package com.rockthejvm.part5polymorphic

import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.syntax.monad.*
import cats.Applicative
import cats.Monad
import cats.effect.IO
import cats.effect.IOApp
import cats.effect.kernel.MonadCancel
import cats.effect.kernel.Outcome.*
import cats.effect.kernel.Poll
import cats.effect.syntax.monadCancel.*
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import com.rockthejvm.utils.general.*

import scala.concurrent.duration.*
import java.lang.module.FindException

object PolymorphicCancellation extends IOApp.Simple:

  trait MyApplicativeError[F[_], E] extends Applicative[F]:
    def raiseError[A](error: E): F[A]
    def handleErrorWith[A](fa: F[A])(f: E => F[A]): F[A]

  trait MyMonadError[F[_], E] extends MyApplicativeError[F, E] with Monad[F]

  trait MyMonadCancel[F[_], E] extends MyMonadError[F, E]:
    def canceled: F[Unit]
    def uncancelable[A](poll: Poll[F] => F[A]): F[A]

  trait MyPoll[F[_]]:
    def apply[A](fa: F[A]): F[A]

  // monadCancel for IO
  val monadCancelIO: MonadCancel[IO, Throwable] = MonadCancel[IO]

  // we can create values
  val molIO: IO[Int]          = monadCancelIO.pure(42)
  val ambitiousMolIO: IO[Int] = monadCancelIO.map(molIO)(_ * 10)

  val mustCompute =
    monadCancelIO.uncancelable { _ =>
      for
        _   <- monadCancelIO.pure("once started, I can't go back...")
        res <- monadCancelIO.pure(56)
      yield res
    }

  def mustComputeGeneral[F[_], E](using mc: MonadCancel[F, E]): F[Int] =
    mc.uncancelable { _ =>
      for
        _   <- mc.pure("once started, I can't go back...")
        res <- mc.pure(56)
      yield res
    }

  val mustComputev2 =
    mustComputeGeneral[IO, Throwable]

  // allow cancellation listeners
  val mustComputeWithListener =
    mustCompute.onCancel(IO("I'm being cancelled!").void)
  val mustComputeWithListenerVw =
    monadCancelIO.onCancel(mustCompute, IO("I'm being cancelled").void)

  // allow finalizers
  val computationWithFinalizers =
    monadCancelIO.guaranteeCase(IO(42)) {
      case Succeeded(fa) => fa.flatMap(a => IO(s"successful: $a").void)
      case Errored(e)    => IO(s"failed: $e").void
      case Canceled()    => IO("canceled").void
    }

  // bracket pattern is specific to MonadCancel
  val computationWithUsage =
    monadCancelIO.bracket(IO(42))(value =>
      IO(s"Using the meaning of life: $value")
    )(value => IO("releasing the meaning of life...").void)

    /** Exercise - generalize a piece of code
      */

  def sleepUnsafe[F[_], E](duration: FiniteDuration)(using
      mc: MonadCancel[F, E]
  ): F[Unit] =
    mc.pure(Thread.sleep(duration.toMillis))

  def inputPassword[F[_], E](using mc: MonadCancel[F, E]): F[String] =
    for
      _   <- mc.pure("Input password:").debug
      _   <- mc.pure("typing password").debug
      _   <- sleepUnsafe[F, E](5.seconds)
      res <- mc.pure("RockTheJVM!").debug
    yield res

  def verifyPassword[F[_], E]: (MonadCancel[F, E]) ?=> String => F[Boolean] =
    (mc: MonadCancel[F, E]) ?=>
      (pw: String) =>
        for
          _   <- mc.pure("Verifying...").debug
          _   <- sleepUnsafe[F, E](2.seconds)
          res <- mc.pure(pw == "RockTheJVM!")
        yield res

  def authFlow[F[_], E](using mc: MonadCancel[F, E]) =
    mc.uncancelable { poll =>
      for
        pw <- poll(inputPassword).onCancel(
          mc.pure("Authentication timed out. Try again later").debug.void
        )
        verified <- verifyPassword(pw)
        _ <-
          if verified then mc.pure("Authentication successful").debug
          else mc.pure("Authentication failed").debug
      yield ()
    }

  val authProgram: IO[Unit] =
    for
      authFib <- authFlow[IO, Throwable].start
      _ <- IO.sleep(3.seconds) >> IO(
        "Authentication timed out. Attempting cancel..."
      ).debug >> authFib.cancel
      _ <- authFib.join
    yield ()

  override def run: IO[Unit] =
    authProgram
