package com.rockthejvm.part4coordination

import cats.syntax.parallel.*
import cats.effect.IO
import cats.effect.IOApp
import scala.util.Random
import scala.concurrent.duration.*
import com.rockthejvm.utils.*
import cats.effect.kernel.Deferred
import scala.collection.immutable.Queue
import cats.effect.kernel.Ref
import com.rockthejvm.part2effects.IOIntroduction.forever
import cats.effect.kernel.Outcome.{Canceled, Succeeded, Errored}

abstract class Mutex:
  def acquire: IO[Unit]
  def release: IO[Unit]

object Mutex:
  type Signal = Deferred[IO, Unit]
  case class State(locked: Boolean, waiting: Queue[Signal])

  val unlocked = State(locked = false, Queue.empty)

  def make: IO[Mutex] =
    IO.ref(unlocked).map(makeWithCancellation)

  def makeSimpleMutex(ref: Ref[IO, State]): Mutex =
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
        ref.modify {
          case State(false, _) =>
            (unlocked, IO.unit)
          case State(true, waiting) =>
            waiting.dequeueOption
              .fold((unlocked, IO.unit)) { case (signal, rest) =>
                (State(locked = true, rest), signal.complete(()).void)
              }
        }.flatten
    }

  def makeWithCancellation(ref: Ref[IO, State]): Mutex =
    new Mutex {
      override def acquire: IO[Unit] =
        def cleanup(signal: Signal) =
          ref.modify { case State(locked, waiting) =>
            val newQueue   = waiting.filterNot(_ eq signal)
            val isBlocking = waiting.exists(_ eq signal)
            val decision =
              if isBlocking
              then IO.unit
              else release
            State(locked, newQueue) -> decision
          }.flatten

        IO.uncancelable { poll =>
          for
            signal <- IO.deferred[Unit]
            state <- ref.modify {
              case State(false, _) =>
                (State(locked = true, Queue.empty), IO.unit)
              case State(true, queue) =>
                (
                  State(locked = true, queue.enqueue(signal)),
                  poll(signal.get).onCancel(cleanup(signal))
                )
            }.flatten
          yield ()
        }

      override def release: IO[Unit] =
        ref.modify {
          case State(false, _) =>
            (unlocked, IO.unit)
          case State(true, waiting) =>
            waiting.dequeueOption
              .fold((unlocked, IO.unit)) { case (signal, rest) =>
                (State(locked = true, rest), signal.complete(()).void)
              }
        }.flatten
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

  def createCancellingTask(id: Int, mutex: Mutex): IO[Int] =
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
      mutex <- Mutex.make
      tasks <- (1 to 10).toList.parTraverse(id =>
        createCancellingTask(id, mutex)
      )
    yield tasks

  val demoCancelWhileBlocked =
    for
      mutex <- Mutex.make
      fib1 <- (
        IO("[fib 1] getting mutex").debug >>
          mutex.acquire >>
          IO("[fib 1] got the mutex, never releasing").debug >> IO.never
      ).start
      fib2 <- (
        IO("[fib 2] sleeping").debug >> IO.sleep(1.second) >>
          IO("[fib 2] trying to get the mutex").debug >>
          mutex.acquire.onCancel(IO("[fib 2] being cancelled").debug.void) >>
          IO("[fib 2] acquired mutex").debug
      ).start
      fib3 <- (
        IO("[fib 3] sleeping").debug >>
          IO.sleep(1500.millis) >>
          IO("[fib 3] trying to get the mutex").debug >>
          mutex.acquire >>
          IO("[fib 3] if this shows, then FAIL").debug
      ).start
      _ <- IO.sleep(2.seconds) >> IO("Cancelling fib 2!").debug >> fib2.cancel
      _ <- fib1.join
      _ <- fib2.join
      _ <- fib3.join
    yield ()

  override def run: IO[Unit] =
    demoCancelWhileBlocked.void
