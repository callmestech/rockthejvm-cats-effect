package com.rockthejvm.part4coordination

import cats.syntax.parallel.*
import cats.effect.IO
import cats.effect.IOApp
import scala.util.Random
import scala.concurrent.duration.*
import com.rockthejvm.utils.*
import cats.effect.kernel.Deferred
import scala.collection.immutable.Queue

abstract class Mutex:
  def acquire: IO[Unit]
  def release: IO[Unit]

object Mutex:
  type Signal = Deferred[IO, Unit]
  case class State(locked: Boolean, waiting: Queue[Signal])

  val unlocked = State(locked = false, Queue.empty)

  def make: IO[Mutex] =
    for ref <- IO.ref(unlocked)
    yield {
      new Mutex {
        override def acquire: IO[Unit] =
          for
            signal <- IO.deferred[Unit]
            state <- ref.modify {
              case State(false, _) =>
                (State(locked = true, Queue.empty), IO.unit)
              case State(true, queue) =>
                (State(locked = true, queue.enqueue(signal)), signal.get)
            }.flatten
          yield ()

        override def release: IO[Unit] =
          for
            state <- ref.get
            _ <- IO.whenA(state.locked) {
              state.waiting.dequeueOption match
                case Some((signal, rest)) =>
                  signal
                    .complete(())
                    .flatMap(_ => ref.set(state.copy(waiting = rest)))
                case None =>
                  ref.set(state.copy(locked = false))
            }
          yield ()
      }
    }

object MutexPlayground extends IOApp.Simple:

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

  def createLockingTask(id: Int, mutex: Mutex): IO[Int] =
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
      mutex <- Mutex.make
      tasks <- (1 to 10).toList.parTraverse(id => createLockingTask(id, mutex))
    yield tasks

  override def run: IO[Unit] =
    demoLockingTasks.void
