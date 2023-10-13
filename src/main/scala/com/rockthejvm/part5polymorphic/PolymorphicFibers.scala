package com.rockthejvm.part5polymorphic

import cats.syntax.flatMap.*
import cats.syntax.functor.*
import cats.effect.syntax.spawn.*
import cats.effect.IOApp
import cats.effect.{IO, MonadCancel, Spawn, Fiber}
import cats.effect.kernel.Outcome
import cats.effect.kernel.Outcome.Succeeded
import cats.effect.kernel.Outcome.Errored
import cats.effect.kernel.Outcome.Canceled

object PolymorphicFibers extends IOApp.Simple:

  // Spawn = create fibers for any effect
  trait MyGenSpawn[F[_], E] extends MonadCancel[F, E]:
    def start[A](fa: F[A]): F[Fiber[F, E, A]]
    def never[A]: F[A] // a forever-suspending effect
    def cede: F[Unit]  // a "yield" effect

    def racePair[A, B](fa: F[A], fb: F[B]): Either[
      (Outcome[F, E, A], Fiber[F, E, B]),
      (Fiber[F, E, A], Outcome[F, E, B])
    ]
  end MyGenSpawn

  trait MySpawn[F[_]] extends MyGenSpawn[F, Throwable]

  val mol                                  = IO(42)
  val fiber: IO[Fiber[IO, Throwable, Int]] = mol.start

  // pure, map/flatMap, raiseError, uncancelable, start
  val spawnIO = Spawn[IO]

  def ioOnSomeThread[A](io: IO[A]) =
    for
      fib    <- spawnIO.start(io)
      result <- fib.join
    yield result

  // generalize
  def effectOnSomeThread[F[_], A](
      fa: F[A]
  )(using spawn: Spawn[F]): F[Outcome[F, Throwable, A]] =
    for
      fib    <- fa.start
      result <- fib.join
    yield result

  val molOnFiber =
    ioOnSomeThread(mol)

  val molOnFiber2 =
    effectOnSomeThread(mol)

  /** Exercise generalize the following code
    */

  def raceGen[F[_], A, B](fa: F[A], fb: F[B])(using
      spawn: Spawn[F]
  ): F[Either[A, B]] =
    spawn.racePair(fa, fb).flatMap {
      case Left((outA, fibB)) =>
        outA match
          case Succeeded(effA) =>
            fibB.cancel >> effA.map(Left(_))
          case Errored(e) =>
            spawn.raiseError(e)
          case Canceled() =>
            fibB.join.flatMap {
              case Succeeded(effB) =>
                effB.map(Right(_))
              case Errored(e) =>
                spawn.raiseError(e)
              case Canceled() =>
                spawn.raiseError(
                  new RuntimeException("Both computations canceled.")
                )
            }
      case Right((fibA, outB)) =>
        outB match
          case Succeeded(effB) =>
            fibA.cancel >> effB.map(Right(_))
          case Errored(e) =>
            spawn.raiseError(e)
          case Canceled() =>
            fibA.join.flatMap {
              case Succeeded(effA) =>
                effA.map(Left(_))
              case Errored(e) =>
                spawn.raiseError(e)
              case Canceled() =>
                spawn.raiseError(
                  new RuntimeException("Both computations canceled.")
                )
            }
    }

  override def run: IO[Unit] = ???
