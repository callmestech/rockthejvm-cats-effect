package com.rockthejvm.part5polymorphic

import cats.syntax.functor.*
import cats.syntax.flatMap.*
import cats.effect.IOApp
import cats.effect.IO
import cats.effect.kernel.{Sync, Temporal, Async, Concurrent}
import java.util.concurrent.Executors
import com.rockthejvm.utils.general.debug
import scala.concurrent.ExecutionContext

object PolymorphicAsync extends IOApp.Simple:

  // Async - asynchronous computations, "suspended" in F
  trait MyAsync[F[_]] extends Sync[F] with Temporal[F]:
    def executionContext: F[ExecutionContext]
    def async[A](
        cb: (Either[Throwable, A] => Unit) => F[Option[F[Unit]]]
    ): F[A]

    def async_[A](cb: (Either[Throwable, A] => Unit) => Unit): F[A] =
      async(kb => map(pure(cb(kb)))(_ => None))

    def evalOn[A](fa: F[A], ec: ExecutionContext): F[A]

    def never[A]: F[A] =
      async_(_ => ())

  end MyAsync

  val asyncIO = Async[IO]

  val threadPool = Executors.newFixedThreadPool(10)
  type Callback[A] = Either[Throwable, A] => Unit

  val asyncMeaningOfLife =
    IO.async_ { (cb: Callback[Int]) =>
      threadPool.execute { () =>
        println(s"[${Thread.currentThread().getName()}] computing an async MOL")
        cb(Right(42))
      }
    }

  val asyncMeaningOfLife2 =
    IO.async { (cb: Callback[Int]) =>
      IO {
        threadPool.execute { () =>
          println(
            s"[${Thread.currentThread().getName()}] computing an async MOL"
          )
          cb(Right(42))
        }
      }.as(
        // finalizer in case the computation gets cancelled
        Some(IO("Cancelled!").debug.void)
      )
    }

  val myExecutionContext = ExecutionContext.fromExecutor(threadPool)
  val asyncMol3 =
    asyncIO
      .evalOn(IO(42).debug, myExecutionContext)
      .guarantee(IO(threadPool.shutdown()))

  // Exercises
  // 1 - implement never and async_ in terms of the big async
  // 2 - tuple two effects with differenct requirements

  def firstEffect[F[_]: Concurrent, A](a: A): F[A] =
    Concurrent[F].pure(a)

  def secondEffect[F[_]: Sync, A](a: A) =
    Sync[F].pure(a)

  def tupledEffect[F[_]: Async, A](a: A): F[(A, A)] =
    firstEffect(a)
      .flatMap(a1 => secondEffect(a).map(a2 => (a1, a2)))

  override def run: IO[Unit] =
    IO.unit
