package com.rockthejvm.part5polymorphic

import cats.syntax.show.*
import cats.syntax.functor.*
import cats.effect.{IOApp, IO, Sync}
import cats.effect.kernel.MonadCancel
import cats.Defer
import cats.Show
import java.io.BufferedReader
import java.io.InputStreamReader

object PolymorphicSync extends IOApp.Simple:

  // suspend computations in IO
  val delayedIO =
    IO.delay {
      println("I'm an effect!")
      42
    }

  // will be evaluated on some specific thread pool for blocking computations
  val blockingIO =
    IO.blocking {
      println("loading...")
      Thread.sleep(1000)
      42
    }

  // Synchronous computation
  trait MySync[F[_]] extends MonadCancel[F, Throwable] with Defer[F]:
    // suspension of a computation - will run on the CE thread pool
    def delay[A](thunk: => A): F[A]
    // runs on the blocking thread pool
    def blocking[A](thunk: => A): F[A]

    // defer comes for free
    def defer[A](thunk: => F[A]): F[A] =
      flatMap(delay(thunk))(identity)

  end MySync

  /** Exercise - write a polymorphic console
    */
  trait Console[F[_]]:
    def println[A: Show](a: A): F[Unit]
    def readLine(): F[String]

  object Console:
    def make[F[_]](using S: Sync[F]): F[Console[F]] =
      S.delay((System.in, System.out)).map { case (in, out) =>
        new Console[F]:
          override def println[A: Show](a: A): F[Unit] =
            S.blocking(out.println(a.show))

          override def readLine(): F[String] =
            val bufferedReader = new BufferedReader(new InputStreamReader(in))
            S.interruptible(bufferedReader.readLine()) // could be replaced with
      }

  // abilities: pure, map/flatMap, raiseError, uncanelable, + delay / blocking
  val deferredIO = IO.defer(delayedIO)

  val consoleReader =
    for
      console <- Console.make[IO]
      _       <- console.println("Hi! What's your name?")
      name    <- console.readLine()
      _       <- console.println(s"It's good to see you $name")
    yield ()

  override def run: IO[Unit] =
    consoleReader
