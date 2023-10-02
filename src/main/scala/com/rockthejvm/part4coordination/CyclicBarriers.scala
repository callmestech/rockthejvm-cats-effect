package com.rockthejvm.part4coordination

import cats.effect.IOApp
import cats.effect.IO

import com.rockthejvm.utils.debug
import cats.effect.kernel.Deferred
import cats.effect.std.CyclicBarrier
import scala.concurrent.duration.*
import scala.util.Random

object CyclicBarriers extends IOApp.Simple:
  /*
   * A cyclic barrier is a coordination primitive that
   * - is initialized with a count
   * - has a single API: await
   *
   * A cyclic barrier will (semantically) block all fibers calling its await()
   * method until we have exactly N fibers waiting,
   * at which point the barrier will unblock all fibers and reset to its original state.
   *
   * Any further fiber will again block until we have exactly N fibers waiting.
   * */

  def createUser(id: Int, barrier: CyclicBarrier[IO]): IO[Unit] =
    for
      _ <- IO.sleep((Random.nextDouble() * 500).toInt.millis)
      _ <- IO(
        s"[user $id] Just hear there's a new social network - signing up for the waitlist..."
      ).debug
      _ <- IO.sleep((Random.nextDouble * 1500).toInt.millis)
      _ <- IO(s"[user $id] On the waitlist now, can't wait!").debug
      _ <-
        barrier.await // block the fiber when there are exactly N users waiting
      _ <- IO(s"[user $id] OMG this is so cool!").debug
    yield ()

  abstract class CBarrier:
    def await: IO[Unit]

  object CBarrier:
    type Signal = Deferred[IO, Unit]

    private case class State(signal: Signal, count: Int)

    def make(count: Int): IO[CBarrier] =
      for
        signal <- IO.deferred[Unit]
        state  <- IO.ref(State(signal, count))
      yield {
        new CBarrier:
          override def await: IO[Unit] =
            for
              newSig <- IO.deferred[Unit]
              _ <- state.modify {
                case State(sig, 1) =>
                  (State(newSig, count), sig.complete(()).void)
                case State(sig, count) =>
                  (State(sig, count - 1), sig.get)
              }.flatten
            yield ()
      }

  end CBarrier

  def task(id: Int, barrier: CBarrier): IO[Unit] =
    for
      _ <- IO(s"[task $id] Prepare...").debug
      _ <- barrier.await
      _ <- IO(s"[task $id] Go go go!").debug
    yield ()

  import cats.syntax.parallel.*

  override def run: IO[Unit] =
    for
      barrier <- CBarrier.make(3)
      _       <- (1 to 6).toList.parTraverse(id => task(id, barrier))
    yield ()
