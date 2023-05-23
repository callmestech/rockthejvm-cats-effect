package com.rockthejvm.part3concurrency

import cats.effect.IOApp
import cats.effect.IO
import scala.concurrent.duration.DurationInt

import com.rockthejvm.utils.debug
import com.rockthejvm.part2effects.IOIntroduction.forever

object CancellfingIOs extends IOApp.Simple:

  /*
   * Cancelling IOs
   * - fib.cancel
   * - IO.race & other APIs
   * - manual canceallation
   */

  val chainOfIOs: IO[Int] =
    IO("waiting").debug >> IO.canceled >> IO(42).debug

  // uncancelable
  // example: online store, payment processor
  // payment process must NOT be cancelled
  val specialPaymentSystem =
    (
      IO("payment running, don't cancel me...").debug >>
        IO.sleep(1.second) >>
        IO("Payment completed").debug
    ).onCancel(IO("MEGA CANCEL OF DOOM").debug.void)

  val cancellationOfDoom =
    for
      fib <- specialPaymentSystem.start
      _   <- IO.sleep(500.millis) >> fib.cancel
      _   <- fib.join
    yield ()

  val atomicPayment =
    IO.uncancelable(_ => specialPaymentSystem) // masking

  val atomicPayment_v2 = specialPaymentSystem.uncancelable // same

  val noCancellationOfDoom =
    for
      fib <- atomicPayment.start
      _ <- IO.sleep(500.millis) >> IO(
        "attempting cancellation..."
      ).debug >> fib.cancel
      _ <- fib.join
    yield ()

  /*
    The uncancelable API is more complex and more general.
    It takes a funciton from Poll[IO] to IO. In the example above, we aren't using that Poll instance.
    The Poll object can be used to mark sections withing the returned effect which CAN BE CANCELED
   */

  /*
    Example: authentication service. Has two parts:
      - input password, can be cancelled, because otherwise we might block indefinitely on user input
      - verify password, CANNOT be cancelled once it's started
   */
  val inputPassword =
    IO("Input password:").debug >>
      IO("typing password").debug >>
      IO.sleep(2.seconds) >>
      IO("RockTheJVM!")

  val verifyPassword =
    (pw: String) =>
      IO("Verifying...").debug >>
        IO.sleep(2.seconds) >>
        IO(pw == "RockTheJVM!")

  val authFlow: IO[Unit] =
    IO.uncancelable { poll =>
      for
        pw <- poll(inputPassword).onCancel(
          IO("Authentication timed out. Try again later").debug.void
        ) // this is cancellable
        verified <- verifyPassword(pw)
        _ <-
          if verified then IO("Authentication successful").debug
          else IO("Authentication failed").debug
      yield ()
    }

  val authProgram =
    for
      authFib <- authFlow.start
      _ <- IO.sleep(3.seconds) >> IO(
        "Authentication timed out. Attempting cancel..."
      ).debug >> authFib.cancel
      _ <- authFib.join
    yield ()

  /*
    Uncancelable calls are MASKS which suppress cancellation
    Poll calls are "gaps opened" in the uncancelable region
   */

  /** Exercises
    */
  // 1
  val cancelBeforeMol =
    IO.canceled >> IO(42).debug

  val uncancelableMol =
    IO.uncancelable(_ => cancelBeforeMol)

  // 2
  val invincibleAuthProgram =
    for
      authFib <- IO.uncancelable(_ => authFlow).start
      _ <- IO.sleep(3.seconds) >> IO(
        "Authentication timeout, attempting cancel..."
      ).debug >> authFib.cancel
      _ <- authFib.join
    yield ()

  // 3
  def threeStepProgram(): IO[Unit] =
    val sequence = IO.uncancelable { poll =>
      poll(IO("cancelable").debug >> IO.sleep(1.second)) >>
        IO("Uncancelable").debug >> IO.sleep(1.second) >>
        poll(IO("second cancelable").debug >> IO.sleep(1.second))
    }

    for
      fib <- sequence.start
      _   <- IO.sleep(1500.millis) >> IO("CANCELING").debug >> fib.cancel
      _   <- fib.join
    yield ()

  override def run: IO[Unit] =
    threeStepProgram().void

end CancellfingIOs
