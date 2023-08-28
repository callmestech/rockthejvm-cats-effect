package com.rockthejvm.part4coordination

import cats.syntax.parallel.*
import cats.effect.{IOApp, IO, Ref}
import com.rockthejvm.utils.debug
import scala.concurrent.duration.*

object Refs extends IOApp.Simple:

  // ref = purely functional atomic reference
  val atomicMol: IO[Ref[IO, Int]]  = Ref[IO].of(42)
  val atomicMol2: IO[Ref[IO, Int]] = IO.ref(42)

  // modifying is an effect
  val increasedMol =
    atomicMol.flatMap(_.set(43)) // thread safe

  // obtain a value
  val mol =
    atomicMol.flatMap(_.get)

  val getAndSetMol =
    atomicMol.flatMap(_.getAndSet(43))

  val fMol =
    atomicMol.flatMap(_.update(_ * 10))

  val updateAndGetMol =
    atomicMol.flatMap(_.updateAndGet(_ * 10))

  def demoConcurrentWorkImpure(): IO[Unit] =
    import cats.syntax.parallel.*
    var count = 0

    def task(workload: String): IO[Unit] =
      val wordCount = workload.split(" ").length
      for
        _        <- IO(s"Counting words for `$workload`: $wordCount").debug
        newCount <- IO(count + wordCount)
        _        <- IO(s"New total: $newCount").debug
        _        <- IO(count += wordCount)
      yield ()

    List(
      "First task contains five words",
      "Second task contains four",
      "Third task three"
    )
      .map(task)
      .parSequence
      .void

  def demoConcurrentWorkPure(): IO[Unit] =
    import cats.syntax.parallel.*

    def task(workload: String, total: Ref[IO, Int]): IO[Unit] =
      val wordCount = workload.split(" ").length
      for
        _        <- IO(s"Counting words for `$workload`: $wordCount").debug
        newCount <- total.updateAndGet(_ + wordCount)
        _        <- IO(s"New total: $newCount").debug
      yield ()
    for
      initialCount <- IO.ref(0)
      _ <- List(
        "First task contains five words",
        "Second task contains four",
        "Third task three"
      ).map(task(_, initialCount)).parSequence
    yield ()

  // exercises
  def tickingClockImpure() =
    var ticks: Long = 0L
    def tickingClocks: IO[Unit] =
      for
        _ <- IO.sleep(1.second)
        _ <- IO(System.currentTimeMillis()).debug
        _ <- IO(ticks += 1)
        _ <- tickingClocks
      yield ()

    def printTicks: IO[Unit] =
      for
        _ <- IO.sleep(5.seconds)
        _ <- IO(s"TICKS: $ticks").debug
        _ <- printTicks
      yield ()
    (tickingClocks, printTicks).parTupled

  def tickingClockPure(): IO[Unit] =
    def tickingClocks(ticks: Ref[IO, Long]): IO[Unit] =
      for
        _ <- IO.sleep(1.second)
        _ <- IO(System.currentTimeMillis()).debug
        _ <- ticks.update(_ + 1)
        _ <- tickingClocks(ticks)
      yield ()

    def printTicks(ticks: Ref[IO, Long]): IO[Unit] =
      for
        _ <- IO.sleep(5.seconds)
        t <- ticks.get
        _ <- IO(s"TICKS: $t").debug
        _ <- printTicks(ticks)
      yield ()
    for
      ref <- IO.ref(0L)
      _   <- (tickingClocks(ref), printTicks(ref)).parTupled
    yield ()

  override def run: IO[Unit] =
    tickingClockPure().void
