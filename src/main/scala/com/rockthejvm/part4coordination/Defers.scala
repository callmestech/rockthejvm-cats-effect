package com.rockthejvm.part4coordination

import cats.syntax.traverse.*
import cats.effect.IOApp
import cats.effect.IO
import cats.effect.kernel.Deferred
import com.rockthejvm.utils.debug
import scala.concurrent.duration.*
import cats.effect.kernel.Ref
import cats.effect.kernel.Outcome
import cats.effect.kernel.Fiber

object Defers extends IOApp.Simple:

  // deferred is a primitive for waiting for an effect, while some other effect completes with a value
  val deferred: IO[Deferred[IO, Int]] =
    Deferred[IO, Int]
  val deferred2: IO[Deferred[IO, Int]] =
    IO.deferred[Int]

  // get blocks the calling fiber (semantically) until some other fiber completes the Deferred with a value
  val reader =
    deferred.flatMap { signal =>
      signal.get
    }

  val writer =
    deferred.flatMap { signal =>
      signal.complete(42)
    }

  val demoDeferred =
    def consumer(signal: Deferred[IO, Int]) =
      for
        _             <- IO("[consumer] waiting for result...").debug
        meaningOfLife <- signal.get
        _             <- IO(s"[consumer] got the result: $meaningOfLife").debug
      yield ()

    def producer(signal: Deferred[IO, Int]) =
      for
        _             <- IO("[producer] crunching numbers...").debug
        _             <- IO.sleep(1.second)
        _             <- IO("[producer] complete: 42").debug
        meaningOfLife <- IO(42)
        _             <- signal.complete(meaningOfLife)
      yield ()

    for
      signal      <- IO.deferred[Int]
      fibConsumer <- consumer(signal).start
      fibProducer <- producer(signal).start
      _           <- fibProducer.join
      _           <- fibConsumer.join
    yield ()

  // simulate downloading some content
  val fileParts =
    List("I ", "love S", "cala", "with Cat", "s Effect!<EOF>")

  val fileNotifierWithRef: IO[Unit] =
    def downloadFile(contentRef: Ref[IO, String]): IO[Unit] =
      fileParts
        .map { part =>
          IO(s"got '$part'").debug >>
            IO.sleep(1.second) >>
            contentRef.update(currentContent => currentContent + part)
        }
        .sequence
        .void

    def notifyFileComplete(contentRef: Ref[IO, String]): IO[Unit] =
      for
        file <- contentRef.get
        _ <-
          if file.endsWith("<EOF>") then IO("File downloaded").debug
          else
            IO("Downloading...").debug >>
              IO.sleep(500.millis) >>
              notifyFileComplete(contentRef) // busy wait!
      yield ()

    for
      contentRef    <- IO.ref("")
      fibDownloader <- downloadFile(contentRef).start
      fibNotifier   <- notifyFileComplete(contentRef).start
      _             <- fibNotifier.join
      _             <- fibDownloader.join
    yield ()

  val fileNotifierWithDeferred: IO[Unit] =
    def notifyFileComplete(signal: Deferred[IO, String]): IO[Unit] =
      for
        _ <- IO("[notifier] downloading...").debug
        _ <- signal.get // blocks until the signal is completed
        _ <- IO("[notifier] File download complete").debug
      yield ()

    def fileDownloader(
        part: String,
        contentRef: Ref[IO, String],
        signal: Deferred[IO, String]
    ): IO[Unit] =
      for
        _ <- IO(s"[downloader] got '$part'").debug
        _ <- IO.sleep(1.second)
        latestContent <- contentRef.updateAndGet(currentContent =>
          currentContent + part
        )
        _ <-
          if latestContent.contains("<EOF>") then signal.complete(latestContent)
          else IO.unit
      yield ()

    for
      contentRef  <- Ref[IO].of("")
      signal      <- Deferred[IO, String]
      notifierFib <- notifyFileComplete(signal).start
      fileTasksFib <- fileParts
        .map(part => fileDownloader(part, contentRef, signal))
        .sequence
        .start
      _ <- notifierFib.join
      _ <- fileTasksFib.join
    yield ()

  /** Exercises
    *
    * (medium) write a small alarm notification with two simultaneous IOs
    *   - one that increments a counter every second (a clock)
    *   - one that waits for the counter to become 10, then prints a message
    *     "time's up"
    */
  val alarmNotifier =
    def notifyWhenComplete(signal: Deferred[IO, Int]): IO[Unit] =
      for
        _ <- IO("[notifier] waiting...").debug
        i <- signal.get
        _ <- IO(s"[notifier] time's up! received $i").debug
      yield ()

    def clock(ref: Ref[IO, Int], signal: Deferred[IO, Int]): IO[Unit] =
      for
        _    <- IO.sleep(1.second)
        time <- ref.updateAndGet(_ + 1)
        _    <- IO(s"[clock] Current time is $time").debug
        _ <-
          if time == 10 then signal.complete(time)
          else clock(ref, signal)
      yield ()

    for
      ref         <- IO.ref(0)
      signal      <- IO.deferred[Int]
      notifierFib <- notifyWhenComplete(signal).start
      clockFib    <- clock(ref, signal).start
      _           <- notifierFib.join
      _           <- clockFib.join
    yield ()

  type RaceResult[A, B] = Either[
    (Outcome[IO, Throwable, A], Fiber[IO, Throwable, B]),
    (Fiber[IO, Throwable, A], Outcome[IO, Throwable, B]),
  ]
  type EitherOutcome[A, B] =
    Either[Outcome[IO, Throwable, A], Outcome[IO, Throwable, B]]

  import cats.syntax.parallel.*

  def ourRacePair[A, B](ioa: IO[A], iob: IO[B]): IO[RaceResult[A, B]] =
    IO.uncancelable { poll =>
      for
        signal <- IO.deferred[EitherOutcome[A, B]]
        fibA <- ioa
          .guaranteeCase(outA => signal.complete(Left(outA)).void)
          .start
        fibB <- iob
          .guaranteeCase(outB => signal.complete(Right(outB)).void)
          .start
        res <- poll(signal.get)
          .onCancel(
            for
              cancelFibA <- fibA.cancel.start
              cancelFibB <- fibB.cancel.start
              _          <- cancelFibA.join
              _          <- cancelFibB.join
            yield ()
          ) // blocking call
      yield res match
        case Left(outA)  => Left(outA -> fibB)
        case Right(outB) => Right(fibA -> outB)
    }

  override def run: IO[Unit] =
    ourRacePair(
      IO.println("[1st racer] Start") >> IO.sleep(3.second) >> IO.println(
        "[1st racer] I win"
      ),
      (IO.println("[2nd racer] Start") >> IO.sleep(5.seconds) >> IO.println(
        "[2nd racer] I win"
      )).onCancel(IO.println("PIZDA"))
    ).void
