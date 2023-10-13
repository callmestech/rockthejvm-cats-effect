package com.rockthejvm.part5polymorphic

import cats.effect.syntax.concurrent.*
import cats.effect.syntax.monadCancel.*
import cats.syntax.parallel.*
import cats.syntax.functor.*
import cats.syntax.flatMap.*
import cats.effect.{IO, IOApp}
import scala.util.Random
import scala.concurrent.duration.*
import com.rockthejvm.utils.*
import cats.effect.kernel.{Ref, Concurrent, Deferred}
import cats.effect.kernel.Outcome.{Canceled, Succeeded, Errored}
import com.rockthejvm.part2effects.IOIntroduction.forever
import scala.collection.immutable.Queue

abstract class MutexGen[F[_]]:
  def acquire: F[Unit]
  def release: F[Unit]

object MutexGen:
  type Signal[F[_]] = Deferred[F, Unit]
  case class State[F[_]](locked: Boolean, waiting: Queue[Signal[F]])

  def unlocked[F[_]] =
    State[F](locked = false, Queue.empty)

  def make[F[_]](using C: Concurrent[F]): F[MutexGen[F]] =
    C.ref(unlocked).map(makeWithCancellation)

  def makeSimpleMutex[F[_]](
      ref: Ref[F, State[F]]
  )(using C: Concurrent[F]): MutexGen[F] =
    new MutexGen {
      override def acquire: F[Unit] =
        for
          signal <- C.deferred[Unit]
          state <- ref.modify {
            case State(false, _) =>
              (State(locked = true, Queue.empty), C.unit)
            case State(true, queue) =>
              (State(locked = true, queue.enqueue(signal)), signal.get)
          }.flatten
        yield ()

      override def release: F[Unit] =
        ref.modify {
          case State(false, _) =>
            (unlocked, C.unit)
          case State(true, waiting) =>
            waiting.dequeueOption
              .fold((unlocked, C.unit)) { case (signal, rest) =>
                (State(locked = true, rest), signal.complete(()).void)
              }
        }.flatten
    }

  def makeWithCancellation[F[_]](
      ref: Ref[F, State[F]]
  )(using C: Concurrent[F]): MutexGen[F] =
    new MutexGen {
      override def acquire: F[Unit] =
        def cleanup(signal: Signal[F]) =
          ref.modify { case State(locked, waiting) =>
            val newQueue = waiting.filterNot(_ eq signal)
            State(locked, newQueue) -> release
          }.flatten

        C.uncancelable { poll =>
          for
            signal <- C.deferred[Unit]
            state <- ref.modify {
              case State(false, _) =>
                (State(locked = true, Queue.empty), C.unit)
              case State(true, queue) =>
                (
                  State(locked = true, queue.enqueue(signal)),
                  poll(signal.get).onCancel(cleanup(signal))
                )
            }.flatten
          yield ()
        }

      override def release: F[Unit] =
        ref.modify {
          case State(false, _) =>
            (unlocked, C.unit)
          case State(true, waiting) =>
            waiting.dequeueOption
              .fold((unlocked, C.unit)) { case (signal, rest) =>
                (State(locked = true, rest), signal.complete(()).void)
              }
        }.flatten
    }

object MutexGenPlayground extends IOApp.Simple:

  val criticalTask =
    IO.sleep(1.second) >> IO(Random.nextInt(100))

  def createNonLockingTask(id: Int): IO[Int] =
    for
      _   <- IO(s"[task $id] working...").debug
      res <- criticalTask
      _   <- IO(s"[task $id] got result: $res").debug
    yield res

  val demoNonLockingTasks =
    (1 to 10).toList.parTraverse(id => createNonLockingTask(id))

  def createLockingTask(id: Int, mutex: MutexGen[IO]): IO[Int] =
    for
      _ <- IO(s"[task $id] waiting for permission...").debug
      _ <-
        mutex.acquire // blocks if the mutex has been acquired by some other fiber
        // critical section
      _   <- IO(s"[task $id] working...").debug
      res <- criticalTask
      _   <- IO(s"[task $id] got result: $res").debug
      _   <- mutex.release
      _   <- IO(s"[task $id] lock released").debug
    yield res

  val demoLockingTasks =
    for
      mutex <- MutexGen.make[IO]
      tasks <- (1 to 10).toList.parTraverse(id => createLockingTask(id, mutex))
    yield tasks

  def createCancellingTask(id: Int, mutex: MutexGen[IO]): IO[Int] =
    if id % 2 == 0
    then createLockingTask(id, mutex)
    else
      for
        fib <- createLockingTask(id, mutex)
          .onCancel(IO(s"[task $id] received cancellation!").debug.void)
          .start
        _   <- IO.sleep(2.seconds) >> fib.cancel
        out <- fib.join
        result <- out match {
          case Succeeded(effect) => effect
          case Errored(_)        => IO(-1)
          case Canceled()        => IO(-2)
        }
      yield result

  val demoCancellingTasks =
    for
      mutex <- MutexGen.make[IO]
      tasks <- (1 to 10).toList.parTraverse(id =>
        createCancellingTask(id, mutex)
      )
    yield tasks

  override def run: IO[Unit] =
    demoCancellingTasks.void
